/**
 * Copyright (c) 2015 IBM Cloudant. All rights reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.cloudant.sync.datastore.sqlcallable;

import com.cloudant.sync.datastore.Attachment;
import com.cloudant.sync.datastore.AttachmentException;
import com.cloudant.sync.datastore.BasicDocumentBody;
import com.cloudant.sync.datastore.BasicDocumentRevision;
import com.cloudant.sync.datastore.ConflictException;
import com.cloudant.sync.datastore.DatastoreException;
import com.cloudant.sync.datastore.DocumentBody;
import com.cloudant.sync.datastore.DocumentException;
import com.cloudant.sync.datastore.DocumentNotFoundException;
import com.cloudant.sync.datastore.DocumentRevisionBuilder;
import com.cloudant.sync.datastore.DocumentRevisionTree;
import com.cloudant.sync.datastore.InvalidDocumentException;
import com.cloudant.sync.datastore.MutableDocumentRevision;
import com.cloudant.sync.sqlite.ContentValues;
import com.cloudant.sync.sqlite.Cursor;
import com.cloudant.sync.sqlite.SQLDatabase;
import com.cloudant.sync.sqlite.SQLQueueCallable;
import com.cloudant.sync.util.CouchUtils;
import com.cloudant.sync.util.DatabaseUtils;
import com.cloudant.sync.util.JSONUtils;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by mike on 17/10/2015.
 */
public abstract class DocumentsCallable<T> extends SQLQueueCallable<T> {

    private static final Logger logger = Logger.getLogger(DocumentsCallable.class.getCanonicalName());

    final AttachmentManager attachmentManager;

    public DocumentsCallable(AttachmentManager attachmentManager) {
        this.attachmentManager = attachmentManager;
    }

    BasicDocumentRevision updateDocumentFromRevision(SQLDatabase db, MutableDocumentRevision rev,
                                                     AttachmentManager.PreparedAndSavedAttachments preparedAndSavedAttachments)
            throws ConflictException, AttachmentException, DocumentNotFoundException, DatastoreException {
        Preconditions.checkNotNull(rev, "DocumentRevision can not be null");

        // update document with new body
        BasicDocumentRevision updated = this.updateDocument(db, rev.docId, rev
                .getSourceRevisionId(), rev.body, true, false);
        // set attachments
        this.attachmentManager.setAttachments(db, updated, preparedAndSavedAttachments);
        // now re-fetch the revision with updated attachments
        BasicDocumentRevision updatedWithAttachments = this.getDocumentInQueue(db, updated.getId(), updated.getRevision());
        return updatedWithAttachments;
    }

    static final String FULL_DOCUMENT_COLS = "docs.docid, docs.doc_id, revid, sequence, json, current, deleted, parent";

    private static final String GET_DOCUMENT_CURRENT_REVISION =
            "SELECT " + FULL_DOCUMENT_COLS + " FROM revs, docs WHERE docs.docid=? AND revs.doc_id=docs.doc_id " +
                    "AND current=1 ORDER BY revid DESC LIMIT 1";

    private static final String GET_DOCUMENT_GIVEN_REVISION =
            "SELECT " + FULL_DOCUMENT_COLS + " FROM revs, docs WHERE docs.docid=? AND revs.doc_id=docs.doc_id " +
                    "AND revid=? LIMIT 1";

    List<BasicDocumentRevision> getDocumentsWithInternalIdsInQueue(SQLDatabase db,
                                                                   final List<Long> docIds)
            throws AttachmentException, DocumentNotFoundException, DocumentException, DatastoreException {

        if (docIds.size() == 0) {
            return Collections.emptyList();
        }

        final String GET_DOCUMENTS_BY_INTERNAL_IDS = "SELECT " + FULL_DOCUMENT_COLS + " FROM revs, docs " +
                "WHERE revs.doc_id IN ( %s ) AND current = 1 AND docs.doc_id = revs.doc_id";

        // Split into batches because SQLite has a limit on the number
        // of placeholders we can use in a single query. 999 is the default
        // value, but it can be lower. It's hard to find this out from Java,
        // so we use a value much lower.
        List<BasicDocumentRevision> result = new ArrayList<BasicDocumentRevision>(docIds.size());

        List<List<Long>> batches = Lists.partition(docIds, SqlConstants
                .SQLITE_QUERY_PLACEHOLDERS_LIMIT);
        for (List<Long> batch : batches) {
            String sql = String.format(
                    GET_DOCUMENTS_BY_INTERNAL_IDS,
                    DatabaseUtils.makePlaceholders(batch.size())
            );
            String[] args = new String[batch.size()];
            for (int i = 0; i < batch.size(); i++) {
                args[i] = Long.toString(batch.get(i));
            }
            result.addAll(getRevisionsFromRawQuery(db, sql, args));
        }

        // Contract is to sort by sequence number, which we need to do
        // outside the sqlDb as we're batching requests.
        Collections.sort(result, new Comparator<BasicDocumentRevision>() {
            @Override
            public int compare(BasicDocumentRevision documentRevision, BasicDocumentRevision documentRevision2) {
                long a = documentRevision.getSequence();
                long b = documentRevision2.getSequence();
                return (int) (a - b);
            }
        });

        return result;
    }

    List<BasicDocumentRevision> getRevisionsFromRawQuery(SQLDatabase db, String sql, String[] args)
            throws DocumentNotFoundException, AttachmentException, DocumentException, DatastoreException {
        List<BasicDocumentRevision> result = new ArrayList<BasicDocumentRevision>();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(sql, args);
            while (cursor.moveToNext()) {
                long sequence = cursor.getLong(3);
                List<? extends Attachment> atts = attachmentManager.attachmentsForRevision(db, sequence);
                BasicDocumentRevision row = getFullRevisionFromCurrentCursor(cursor, atts);
                result.add(row);
            }
        } catch (SQLException e) {
            throw new DatastoreException(e);
        } finally {
            DatabaseUtils.closeCursorQuietly(cursor);
        }
        return result;
    }

    private static BasicDocumentRevision getFullRevisionFromCurrentCursor(Cursor cursor,
                                                                          List<? extends Attachment> attachments) {
        String docId = cursor.getString(cursor.getColumnIndex("docid"));
        long internalId = cursor.getLong(cursor.getColumnIndex("doc_id"));
        String revId = cursor.getString(cursor.getColumnIndex("revid"));
        long sequence = cursor.getLong(cursor.getColumnIndex("sequence"));
        byte[] json = cursor.getBlob(cursor.getColumnIndex("json"));
        boolean current = cursor.getInt(cursor.getColumnIndex("current")) > 0;
        boolean deleted = cursor.getInt(cursor.getColumnIndex("deleted")) > 0;

        long parent = -1L;
        if (cursor.columnType(cursor.getColumnIndex("parent")) == Cursor.FIELD_TYPE_INTEGER) {
            parent = cursor.getLong(cursor.getColumnIndex("parent"));
        } else if (cursor.columnType(cursor.getColumnIndex("parent")) == Cursor.FIELD_TYPE_NULL) {
        } else {
            throw new RuntimeException("Unexpected type: " + cursor.columnType(cursor.getColumnIndex("parent")));
        }

        DocumentRevisionBuilder builder = new DocumentRevisionBuilder()
                .setDocId(docId)
                .setRevId(revId)
                .setBody(BasicDocumentBody.bodyWith(json))
                .setDeleted(deleted)
                .setSequence(sequence)
                .setInternalId(internalId)
                .setCurrent(current)
                .setParent(parent)
                .setAttachments(attachments);

        return builder.build();
    }

    /**
     * Gets a document with the specified ID at the specified revision.
     *
     * @param db  The database from which to load the document
     * @param id  The id of the document to be loaded
     * @param rev The revision of the document to load
     * @return The loaded document revision.
     * @throws AttachmentException       If an error occurred loading the document's attachment
     * @throws DocumentNotFoundException If the document was not found.
     */
    public BasicDocumentRevision getDocumentInQueue(SQLDatabase db, String id, String rev)
            throws AttachmentException, DocumentNotFoundException, DatastoreException {
        Cursor cursor = null;
        try {
            String[] args = (rev == null) ? new String[]{id} : new String[]{id, rev};
            String sql = (rev == null) ? GET_DOCUMENT_CURRENT_REVISION : GET_DOCUMENT_GIVEN_REVISION;
            cursor = db.rawQuery(sql, args);
            if (cursor.moveToFirst()) {
                long sequence = cursor.getLong(3);
                List<? extends Attachment> atts = attachmentManager.attachmentsForRevision(db, sequence);
                return getFullRevisionFromCurrentCursor(cursor, atts);
            } else {
                throw new DocumentNotFoundException(id, rev);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error getting document with id: " + id + "and rev " + rev, e);
            throw new DatastoreException(String.format("Could not find document with id %s at revision %s", id, rev), e);
        } finally {
            DatabaseUtils.closeCursorQuietly(cursor);
        }
    }

    BasicDocumentRevision updateDocument(SQLDatabase db, String docId,
                                         String prevRevId,
                                         final DocumentBody body,
                                         boolean validateBody,
                                         boolean copyAttachments)
            throws ConflictException, AttachmentException, DocumentNotFoundException, DatastoreException {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(docId),
                "Input document id can not be empty");
        Preconditions.checkArgument(!Strings.isNullOrEmpty(prevRevId),
                "Input previous revision id can not be empty");
        Preconditions.checkNotNull(body, "Input document body can not be null");
        if (validateBody) {
            validateDBBody(body);
        }
        CouchUtils.validateRevisionId(prevRevId);

        BasicDocumentRevision preRevision = this.getDocumentInQueue(db, docId, prevRevId);

        if (!preRevision.isCurrent()) {
            throw new ConflictException("Revision to be updated is not current revision.");
        }

        setCurrent(db, preRevision, false);
        String newRevisionId = insertNewWinnerRevision(db, body, preRevision, copyAttachments);
        return this.getDocumentInQueue(db, preRevision.getId(), newRevisionId);
    }

    BasicDocumentRevision deleteDocumentInQueue(SQLDatabase db, final String docId,
                                                final String prevRevId) throws ConflictException, DocumentNotFoundException, DatastoreException {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(docId),
                "Input document id can not be empty");
        Preconditions.checkArgument(!Strings.isNullOrEmpty(prevRevId),
                "Input previous revision id can not be empty");

        CouchUtils.validateRevisionId(prevRevId);

        BasicDocumentRevision prevRevision;
        try {
            prevRevision = getDocumentInQueue(db, docId, prevRevId);
        } catch (AttachmentException e) {
            throw new DocumentNotFoundException(e);
        }


        DocumentRevisionTree revisionTree;
        try {
            revisionTree = getAllRevisionsOfDocumentInQueue(db, docId);
        } catch (AttachmentException e) {
            // we can't get load the document, due to an attachment error,
            // throw an exception saying we couldn't find the document.
            throw new DocumentNotFoundException(e);
        }

        if (!revisionTree.leafRevisionIds().contains(prevRevId)) {
            throw new ConflictException("Document has newer revisions than the revision " +
                    "passed to delete; get the newest revision of the document and try again.");
        }

        if (prevRevision.isDeleted()) {
            throw new DocumentNotFoundException("Previous Revision is already deleted");
        }
        setCurrent(db, prevRevision, false);
        String newRevisionId = CouchUtils.generateNextRevisionId(prevRevision.getRevision());
        // Previous revision to be deleted could be winner revision ("current" == true),
        // or a non-winner leaf revision ("current" == false), the new inserted
        // revision must have the same flag as it previous revision.
        // Deletion of non-winner leaf revision is mainly used when resolving
        // conflicts.
        DocumentsCallable.InsertRevisionOptions options = new DocumentsCallable.InsertRevisionOptions();
        options.docNumericId = prevRevision.getInternalNumericId();
        options.revId = newRevisionId;
        options.parentSequence = prevRevision.getSequence();
        options.deleted = true;
        options.current = prevRevision.isCurrent();
        options.data = JSONUtils.EMPTY_JSON;
        options.available = false;
        insertRevision(db, options);


        try {
            //get the deleted document revision to return to the user
            return getDocumentInQueue(db, prevRevision.getId(), newRevisionId);
        } catch (AttachmentException e) {
            //throw document not found since we failed to load the document
            throw new DocumentNotFoundException(e);
        }
    }

    void setCurrent(SQLDatabase db, BasicDocumentRevision winner, boolean currentValue) {
        ContentValues updateContent = new ContentValues();
        updateContent.put("current", currentValue ? 1 : 0);
        String[] whereArgs = new String[]{String.valueOf(winner.getSequence())};
        db.update("revs", updateContent, "sequence=?", whereArgs);
    }

    void validateDBBody(DocumentBody body) {
        for (String name : body.asMap().keySet()) {
            if (name.startsWith("_")) {
                throw new InvalidDocumentException("Field name start with '_' is not allowed. ");
            }
        }
    }

    private String insertNewWinnerRevision(SQLDatabase db, DocumentBody newWinner,
                                           BasicDocumentRevision oldWinner,
                                           boolean copyAttachments)
            throws AttachmentException, DatastoreException {
        String newRevisionId = CouchUtils.generateNextRevisionId(oldWinner.getRevision());

        DocumentsCallable.InsertRevisionOptions options = new DocumentsCallable.InsertRevisionOptions();
        options.docNumericId = oldWinner.getInternalNumericId();
        options.revId = newRevisionId;
        options.parentSequence = oldWinner.getSequence();
        options.deleted = false;
        options.current = true;
        options.data = newWinner.asBytes();
        options.available = true;

        if (copyAttachments) {
            this.insertRevisionAndCopyAttachments(db, options);
        } else {
            this.insertRevision(db, options);
        }

        return newRevisionId;
    }

    private long insertRevisionAndCopyAttachments(SQLDatabase db, DocumentsCallable.InsertRevisionOptions options) throws AttachmentException, DatastoreException {
        long newSequence = insertRevision(db, options);
        //always copy attachments
        this.attachmentManager.copyAttachments(db, options.parentSequence, newSequence);
        // inserted revision and copied attachments, so we are done
        return newSequence;
    }

    DocumentRevisionTree getAllRevisionsOfDocumentInQueue(SQLDatabase db, String docId)
            throws DocumentNotFoundException, AttachmentException, DatastoreException {
        String sql = "SELECT " + FULL_DOCUMENT_COLS + " FROM revs, docs " +
                "WHERE docs.docid=? AND revs.doc_id = docs.doc_id ORDER BY sequence ASC";

        String[] args = {docId};
        Cursor cursor = null;

        try {
            DocumentRevisionTree tree = new DocumentRevisionTree();
            cursor = db.rawQuery(sql, args);
            while (cursor.moveToNext()) {
                long sequence = cursor.getLong(3);
                List<? extends Attachment> atts = attachmentManager.attachmentsForRevision(db, sequence);
                BasicDocumentRevision rev = getFullRevisionFromCurrentCursor(cursor, atts);
                logger.finer("Rev: " + rev);
                tree.add(rev);
            }
            return tree;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error getting all revisions of document", e);
            throw new DatastoreException("DocumentRevisionTree not found with id: " + docId, e);
        } finally {
            DatabaseUtils.closeCursorQuietly(cursor);
        }

    }

    long insertRevision(SQLDatabase db, DocumentsCallable.InsertRevisionOptions options) {

        long newSequence;
        ContentValues args = new ContentValues();
        args.put("doc_id", options.docNumericId);
        args.put("revid", options.revId);
        // parent field is a foreign key
        if (options.parentSequence > 0) {
            args.put("parent", options.parentSequence);
        }
        args.put("current", options.current);
        args.put("deleted", options.deleted);
        args.put("available", options.available);
        args.put("json", options.data);
        logger.fine("New revision inserted: " + options.docNumericId + ", " + options.revId);
        newSequence = db.insert("revs", args);
        if (newSequence < 0) {
            throw new IllegalStateException("Unknown error inserting new updated doc, please check log");
        }


        return newSequence;
    }

    protected long insertDocumentID(SQLDatabase db, String docId) {
        ContentValues args = new ContentValues();
        args.put("docid", docId);
        return db.insert("docs", args);
    }

    class InsertRevisionOptions {
        // doc_id in revs table
        public long docNumericId;
        public String revId;
        public long parentSequence;
        // is revision deleted?
        public boolean deleted;
        // is revision current? ("winning")
        public boolean current;
        public byte[] data;
        public boolean available;


        @Override
        public String toString() {
            return "InsertRevisionOptions{" +
                    ", docNumericId=" + docNumericId +
                    ", revId='" + revId + '\'' +
                    ", parentSequence=" + parentSequence +
                    ", deleted=" + deleted +
                    ", current=" + current +
                    ", available=" + available +
                    '}';
        }
    }
}
