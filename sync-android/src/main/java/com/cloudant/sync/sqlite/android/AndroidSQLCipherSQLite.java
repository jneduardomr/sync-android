/**
 * Copyright (c) 2013 Cloudant, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.cloudant.sync.sqlite.android;

/**
 * Extend SQLDatabase class with support for SQLCipher encrypted databases.
 * Created by estebanmlaver on 2/19/15.
 */

import com.cloudant.sync.sqlite.ContentValues;
import com.cloudant.sync.sqlite.Cursor;
import com.cloudant.sync.sqlite.SQLDatabase;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

import net.sqlcipher.database.SQLiteConstraintException;
import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteException;

public class AndroidSQLCipherSQLite extends SQLDatabase {

    SQLiteDatabase database = null;
    private Thread threadWhichOpened = null;
    private ThreadLocal<Boolean> appearsOpen = null;

    public static AndroidSQLCipherSQLite createAndroidSQLite(String path, char[] passphrase) {
        //Load required libraries for SQL Cipher
        //SQLiteDatabase.loadLibs(context);

        //Call SQLCipher-based method open database or create if database not found
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(path, passphrase, null);

        return new AndroidSQLCipherSQLite(db);
    }

    public static AndroidSQLCipherSQLite openAndroidSQLite(String path, char[] passphrase) {

        //Call SQLCipher-based method open database or create if database not found
        SQLiteDatabase db = SQLiteDatabase.openDatabase(path, passphrase, null, SQLiteDatabase.OPEN_READWRITE);

        return new AndroidSQLCipherSQLite(db);
    }

    public AndroidSQLCipherSQLite(final SQLiteDatabase database) {
        this.database = database;
        this.threadWhichOpened = Thread.currentThread();
        this.appearsOpen = new ThreadLocal<Boolean>(){
            @Override
            protected Boolean initialValue() {
                return database.isOpen();
            }
        };
    }


    //@Override
    public void compactDatabase() {
        database.execSQL("VACUUM");
    }

    //SQLCipher does not contain open() method
    //@Override
    //public void open() {
        // database should be already opened
    //}

    @Override
    public void close() {
        // Since the JavaSE version of this needs to have each thread that operated on the db
        // close its connection to the db. Since android does not have ThreadLocal connections
        // the connection only needs to be closed once.
        // To maintain compatibility with JavaSE unless the current thread opened the connection,
        // the db only appears closed to the thread that called close
        if(threadWhichOpened == Thread.currentThread())
            this.database.close();
        appearsOpen.set(Boolean.FALSE);
    }

    // This implementation of isOpen will only return true if the database is open AND the current
    // thread has not called the close() method on this object previously, this makes it compatible
    // with the JavaSE SQLiteWrapper, where each thread closes its own connection.
    @Override
    public boolean isOpen() {
        return this.database.isOpen() && this.appearsOpen.get();
    }

    @Override
    public void beginTransaction() {
        this.database.beginTransaction();
    }

    @Override
    public void endTransaction() {
        this.database.endTransaction();
    }

    @Override
    public void setTransactionSuccessful() {
        this.database.setTransactionSuccessful();
    }

    @Override
    public void execSQL(String sql)  {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(sql.trim()),
                "Input SQL can not be empty String.");
        this.database.execSQL(sql);
    }

    //@Override
    public int status(int operation, boolean reset) {
        return 0;
    }

    //@Override
    public void changePassword(String password) throws SQLiteException {

    }

    //@Override
    public void changePassword(char[] password) throws SQLiteException {

    }

    @Override
    public void execSQL(String sql, Object[] bindArgs) {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(sql.trim()),
                "Input SQL can not be empty String.");
        this.database.execSQL(sql, bindArgs);
    }

    @Override
    public int getVersion() {
        return this.database.getVersion();
    }

    @Override
    public void open() {
    }

    //@Override
    public int update(String table, ContentValues args, String whereClause, String[] whereArgs) {
        return this.database.update(table, this.createAndroidContentValues(args), whereClause, whereArgs);
    }

    @Override
    public Cursor rawQuery(String sql, String[] values) {
        return new AndroidSQLiteCursor(this.database.rawQuery(sql, values));
    }


    //@Override
    //public Cursor rawQuery(String sql, String[] selectionArgs) {
        //return new AndroidSQLiteCursor(this.database.rawQuery(sql, selectionArgs));
    //    return super.rawQuery(sql, selectionArgs);
    //}

    @Override
    public int delete(String table, String whereClause, String[] whereArgs) {
        return this.database.delete(table, whereClause, whereArgs);
    }

    //@Override
    public long insert(String table, ContentValues args) {
        return this.insertWithOnConflict(table, args, SQLiteDatabase.CONFLICT_NONE);
    }

    //@Override
    public long insertWithOnConflict(String table, ContentValues initialValues, int conflictAlgorithm) {
        //android DB will thrown an exception rather than return a -1 row id if there is a failure
        // so we catch constraintException and return -1
        try {
            return this.database.insertWithOnConflict(table, null,
                    createAndroidContentValues(initialValues), conflictAlgorithm);
        } catch (SQLiteConstraintException sqlce){
            return -1;
        }
    }

    private android.content.ContentValues createAndroidContentValues(ContentValues values) {
        android.content.ContentValues newValues = new android.content.ContentValues(values.size());
        for(String key : values.keySet()) {
            Object value = values.get(key);
            if(value instanceof Boolean) {
                newValues.put(key, (Boolean)value);
            } else if(value instanceof Byte) {
                newValues.put(key, (Byte)value);
            } else if(value instanceof byte[]) {
                newValues.put(key, (byte[])value);
            } else if(value instanceof Double) {
                newValues.put(key, (Double)value);
            } else if(value instanceof Float) {
                newValues.put(key, (Float)value);
            } else if(value instanceof Integer) {
                newValues.put(key, (Integer)value);
            } else if(value instanceof Long) {
                newValues.put(key, (Long)value);
            } else if(value instanceof Short) {
                newValues.put(key, (Short)value);
            } else if(value instanceof String) {
                newValues.put(key, (String)value);
            } else if( value == null) {
                newValues.putNull(key);
            } else {
                throw new IllegalArgumentException("Unsupported data type: " + value.getClass());
            }
        }
        return newValues;
    }

    @Override
    protected void finalize() throws Throwable{
        super.finalize();
        if(this.database.isOpen())
            this.database.close();
    }
}