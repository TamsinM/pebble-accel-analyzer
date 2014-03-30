package com.whitneyindustries.acceldump.queue;

import com.whitneyindustries.acceldump.db.AccelDataContract.QueuedMessageEntry;
import com.whitneyindustries.acceldump.db.AccelDataDbHelper;
import com.whitneyindustries.acceldump.model.AccelData;
import com.whitneyindustries.acceldump.util.JsonHttpClient;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import android.util.Pair;

import org.json.JSONArray;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Please forgive me for implementing yet another reliable messaging queue
 */
public class DbBackedAccelQueue implements SendQueue {
    private static final String TAG = DbBackedAccelQueue.class.getSimpleName();
    private Queue<AccelData> toSend = new ConcurrentLinkedQueue<AccelData>();
    private Queue<Pair<String, Future<Boolean>>> pending = new ConcurrentLinkedQueue<Pair<String, Future<Boolean>>>();

    private JsonHttpClient httpClient;
    private SQLiteDatabase db;


    public DbBackedAccelQueue(Context context) {
        db = (new AccelDataDbHelper(context)).getWritableDatabase();
    }

    protected JsonHttpClient getHttpClient() {
        return new JsonHttpClient();
    }

    @Override
    public void addNewReading(AccelData reading) {
        toSend.offer(reading);
    }

    @Override
    public int sendUnsent() {
        httpClient = getHttpClient();
        int msgsSent = 0;
        long now = System.currentTimeMillis();
        while (toSend.size() >= 1008) {
            msgsSent += sendReadings();
        }
        msgsSent += sendOldReadings();
        persistFailed(now);
        httpClient.shutdown();
        return msgsSent;
    }

    private int sendReadings() {
        JSONArray readingsJson = new JSONArray();
        AccelData a = toSend.poll();
        int msgsSent;
        for (msgsSent = 0; a != null; msgsSent++) {
            readingsJson.put(a.toJson());
            a = toSend.poll();
        }
        final String byteString = readingsJson.toString();
        Future<Boolean> result = httpClient.post(byteString);
        pending.offer(new Pair<String, Future<Boolean>>(byteString, result));
        return 1;
    }

    private int sendOldReadings() {
        String sortOrder = QueuedMessageEntry.COLUMN_NAME_GEN_TIME + " DESC";

        int msgsSent = 0;
        Cursor c = null;
        try {
            c = db.query(QueuedMessageEntry.TABLE_NAME,
                         null,
                         null,
                         null,
                         null,
                         null,
                         sortOrder);

            for (msgsSent = 0; c.moveToNext(); msgsSent++) {
                long id = c.getLong(c.getColumnIndexOrThrow(QueuedMessageEntry._ID));
                String msg = c.getString(c.getColumnIndexOrThrow(QueuedMessageEntry.COLUMN_NAME_MESSAGE));
                long genTime = c.getLong(c.getColumnIndexOrThrow(QueuedMessageEntry.COLUMN_NAME_GEN_TIME));

                Future<Boolean> result = httpClient.post(msg);
                pending.offer(new Pair<String, Future<Boolean>>(msg, result));

                db.delete(QueuedMessageEntry.TABLE_NAME,
                          QueuedMessageEntry._ID + " = ?",
                          new String[] { id + "" });
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return msgsSent;
    }

    private void saveReadingToDb(String msg, long genTime) {
        ContentValues values = new ContentValues();
        values.put(QueuedMessageEntry.COLUMN_NAME_GEN_TIME, genTime);
        values.put(QueuedMessageEntry.COLUMN_NAME_MESSAGE, msg);
        values.put(QueuedMessageEntry.COLUMN_NAME_RETRIES, 0);
        db.insert(QueuedMessageEntry.TABLE_NAME, null, values);
    }

    @Override
    public void persistFailed(long now) {
        Pair<String, Future<Boolean>> pendingEntry = pending.poll();
        int i = 0;
        while (pendingEntry != null) {
            try {
                boolean success = pendingEntry.second.get(60, TimeUnit.SECONDS);
                if (!success) {
                    Log.d(TAG, "posting reading bundle failed, going to save to DB for later xmit");
                    saveReadingToDb(pendingEntry.first, now);
                }
            } catch (InterruptedException e) {
            } catch (Exception e) {
                Log.e(TAG, "problem getting reading");
            }
            pendingEntry = pending.poll();
        }
    }
}

