package com.xinyu.companion.data.local;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.room.Room;
import androidx.sqlite.db.framework.FrameworkSQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DatabaseMigrationTest {
    private Context context;
    private String databaseName;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        databaseName = "migration-" + UUID.randomUUID() + ".db";
    }

    @After
    public void tearDown() {
        context.deleteDatabase(databaseName);
    }

    @Test
    public void migration2To3PreservesMessagesAndAddsReplyRelation() throws Exception {
        try (FrameworkSQLiteDatabase database = openVersion2Database()) {
            AppDatabase.Companion.getMIGRATION_2_3().migrate(database);

            boolean foundReplyColumn = false;
            try (Cursor cursor = database.query("PRAGMA table_info(messages)")) {
                int nameIndex = cursor.getColumnIndexOrThrow("name");
                while (cursor.moveToNext()) {
                    if ("replyToMessageId".equals(cursor.getString(nameIndex))) {
                        foundReplyColumn = true;
                    }
                }
            }
            assertThat(foundReplyColumn).isTrue();

            try (Cursor cursor = database.query(
                "SELECT content, replyToMessageId FROM messages WHERE id = 'm1'"
            )) {
                assertThat(cursor.moveToFirst()).isTrue();
                assertThat(cursor.getString(0)).isEqualTo("保留我");
                assertThat(cursor.isNull(1)).isTrue();
            }
        }
    }

    @Test
    public void migration1To2ClearsUnrecoverableCorruptedTurns() throws Exception {
        try (FrameworkSQLiteDatabase database = openVersion2Database()) {
            AppDatabase.Companion.getMIGRATION_1_2().migrate(database);

            try (Cursor cursor = database.query("SELECT COUNT(*) FROM messages")) {
                assertThat(cursor.moveToFirst()).isTrue();
                assertThat(cursor.getInt(0)).isEqualTo(0);
            }
        }
    }

    @Test
    public void migration3To4PreservesMessagesAddsConstraintAndIndexes() throws Exception {
        try (FrameworkSQLiteDatabase database = openVersion3Database()) {
            AppDatabase.Companion.getMIGRATION_3_4().migrate(database);
            database.setForeignKeyConstraintsEnabled(true);

            try (Cursor cursor = database.query(
                "SELECT content FROM messages WHERE id = 'm1'"
            )) {
                assertThat(cursor.moveToFirst()).isTrue();
                assertThat(cursor.getString(0)).isEqualTo("旧消息");
            }

            boolean foundConversationForeignKey = false;
            try (Cursor cursor = database.query("PRAGMA foreign_key_list(messages)")) {
                int tableIndex = cursor.getColumnIndexOrThrow("table");
                while (cursor.moveToNext()) {
                    if ("conversations".equals(cursor.getString(tableIndex))) {
                        foundConversationForeignKey = true;
                    }
                }
            }
            assertThat(foundConversationForeignKey).isTrue();

            boolean foundMemoryIndex = false;
            try (Cursor cursor = database.query("PRAGMA index_list(memories)")) {
                int nameIndex = cursor.getColumnIndexOrThrow("name");
                while (cursor.moveToNext()) {
                    if ("index_memories_companionId_userConfirmed_importance_createdAt"
                        .equals(cursor.getString(nameIndex))) {
                        foundMemoryIndex = true;
                    }
                }
            }
            assertThat(foundMemoryIndex).isTrue();

            database.execSQL("DELETE FROM conversations WHERE id = 'c1'");
            try (Cursor cursor = database.query("SELECT COUNT(*) FROM messages")) {
                assertThat(cursor.moveToFirst()).isTrue();
                assertThat(cursor.getInt(0)).isEqualTo(0);
            }
        }
    }

    @Test
    public void migration4To5PreservesLegacyMemoriesAndValidatesSchema() {
        openVersion4Database();

        AppDatabase database = Room.databaseBuilder(
            context,
            AppDatabase.class,
            databaseName
        )
            .addMigrations(
                AppDatabase.Companion.getMIGRATION_4_5(),
                AppDatabase.Companion.getMIGRATION_5_6()
            )
            .allowMainThreadQueries()
            .build();
        try {
            database.getOpenHelper().getWritableDatabase();

            try (Cursor cursor = database.getOpenHelper()
                .getReadableDatabase()
                .query(
                    "SELECT statement, sourceKind, status FROM memory_items "
                        + "WHERE id = 'memory-legacy'"
                )) {
                assertThat(cursor.moveToFirst()).isTrue();
                assertThat(cursor.getString(0)).isEqualTo("喜欢安静");
                assertThat(cursor.getString(1)).isEqualTo("MANUAL");
                assertThat(cursor.getString(2)).isEqualTo("ACTIVE");
            }

            try (Cursor cursor = database.getOpenHelper()
                .getReadableDatabase()
                .query("SELECT COUNT(*) FROM memory_items_fts")) {
                assertThat(cursor.moveToFirst()).isTrue();
                assertThat(cursor.getInt(0)).isEqualTo(1);
            }

            try (Cursor cursor = database.getOpenHelper()
                .getReadableDatabase()
                .query("SELECT COUNT(*) FROM memory_jobs")) {
                assertThat(cursor.moveToFirst()).isTrue();
                assertThat(cursor.getInt(0)).isEqualTo(0);
            }

            try (Cursor cursor = database.getOpenHelper()
                .getReadableDatabase()
                .query("SELECT COUNT(*) FROM perception_events")) {
                assertThat(cursor.moveToFirst()).isTrue();
                assertThat(cursor.getInt(0)).isEqualTo(0);
            }

            try (Cursor cursor = database.getOpenHelper()
                .getReadableDatabase()
                .query("SELECT COUNT(*) FROM notification_sources")) {
                assertThat(cursor.moveToFirst()).isTrue();
                assertThat(cursor.getInt(0)).isEqualTo(0);
            }
        } finally {
            database.close();
        }
    }

    private FrameworkSQLiteDatabase openVersion2Database() {
        File file = context.getDatabasePath(databaseName);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        SQLiteDatabase sqlite = SQLiteDatabase.openOrCreateDatabase(file, null);
        sqlite.execSQL(
            "CREATE TABLE messages ("
                + "id TEXT NOT NULL PRIMARY KEY,"
                + "conversationId TEXT NOT NULL,"
                + "role TEXT NOT NULL,"
                + "sequence INTEGER NOT NULL,"
                + "content TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "clientMessageId TEXT,"
                + "createdAt INTEGER NOT NULL,"
                + "updatedAt INTEGER NOT NULL)"
        );
        sqlite.execSQL(
            "INSERT INTO messages VALUES "
                + "('m1','c1','USER',1,'保留我','COMPLETED',NULL,1,1)"
        );
        return new FrameworkSQLiteDatabase(sqlite);
    }

    private FrameworkSQLiteDatabase openVersion3Database() {
        File file = context.getDatabasePath(databaseName);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        SQLiteDatabase sqlite = SQLiteDatabase.openOrCreateDatabase(file, null);
        sqlite.execSQL(
            "CREATE TABLE conversations ("
                + "id TEXT NOT NULL PRIMARY KEY,"
                + "companionId TEXT NOT NULL,"
                + "title TEXT NOT NULL,"
                + "createdAt INTEGER NOT NULL,"
                + "updatedAt INTEGER NOT NULL)"
        );
        sqlite.execSQL(
            "CREATE TABLE messages ("
                + "id TEXT NOT NULL PRIMARY KEY,"
                + "conversationId TEXT NOT NULL,"
                + "role TEXT NOT NULL,"
                + "sequence INTEGER NOT NULL,"
                + "content TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "clientMessageId TEXT,"
                + "replyToMessageId TEXT,"
                + "createdAt INTEGER NOT NULL,"
                + "updatedAt INTEGER NOT NULL)"
        );
        sqlite.execSQL(
            "CREATE TABLE memories ("
                + "id TEXT NOT NULL PRIMARY KEY,"
                + "companionId TEXT NOT NULL,"
                + "type TEXT NOT NULL,"
                + "content TEXT NOT NULL,"
                + "importance INTEGER NOT NULL,"
                + "userConfirmed INTEGER NOT NULL,"
                + "createdAt INTEGER NOT NULL)"
        );
        sqlite.execSQL(
            "INSERT INTO conversations VALUES "
                + "('c1','companion-linyu','测试',1,1)"
        );
        sqlite.execSQL(
            "INSERT INTO messages VALUES "
                + "('m1','c1','USER',1,'旧消息','COMPLETED',NULL,NULL,1,1)"
        );
        sqlite.execSQL(
            "INSERT INTO memories VALUES "
                + "('memory-1','companion-linyu','fact','喜欢安静',3,1,1)"
        );
        return new FrameworkSQLiteDatabase(sqlite);
    }

    private void openVersion4Database() {
        File file = context.getDatabasePath(databaseName);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        SQLiteDatabase sqlite = SQLiteDatabase.openOrCreateDatabase(file, null);
        sqlite.execSQL(
            "CREATE TABLE conversations ("
                + "id TEXT NOT NULL PRIMARY KEY,"
                + "companionId TEXT NOT NULL,"
                + "title TEXT NOT NULL,"
                + "createdAt INTEGER NOT NULL,"
                + "updatedAt INTEGER NOT NULL)"
        );
        sqlite.execSQL(
            "CREATE INDEX index_conversations_companionId_updatedAt "
                + "ON conversations (companionId, updatedAt)"
        );
        sqlite.execSQL(
            "CREATE TABLE messages ("
                + "id TEXT NOT NULL PRIMARY KEY,"
                + "conversationId TEXT NOT NULL,"
                + "role TEXT NOT NULL,"
                + "sequence INTEGER NOT NULL,"
                + "content TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "clientMessageId TEXT,"
                + "replyToMessageId TEXT,"
                + "createdAt INTEGER NOT NULL,"
                + "updatedAt INTEGER NOT NULL,"
                + "FOREIGN KEY(conversationId) REFERENCES conversations(id) "
                + "ON UPDATE NO ACTION ON DELETE CASCADE)"
        );
        sqlite.execSQL(
            "CREATE UNIQUE INDEX index_messages_conversationId_sequence "
                + "ON messages (conversationId, sequence)"
        );
        sqlite.execSQL(
            "CREATE UNIQUE INDEX index_messages_clientMessageId "
                + "ON messages (clientMessageId)"
        );
        sqlite.execSQL(
            "CREATE TABLE memories ("
                + "id TEXT NOT NULL PRIMARY KEY,"
                + "companionId TEXT NOT NULL,"
                + "type TEXT NOT NULL,"
                + "content TEXT NOT NULL,"
                + "importance INTEGER NOT NULL,"
                + "userConfirmed INTEGER NOT NULL,"
                + "createdAt INTEGER NOT NULL)"
        );
        sqlite.execSQL(
            "CREATE INDEX index_memories_companionId_userConfirmed_importance_createdAt "
                + "ON memories (companionId, userConfirmed, importance, createdAt)"
        );
        sqlite.execSQL(
            "INSERT INTO conversations VALUES "
                + "('c1','companion-linyu','测试',1,1)"
        );
        sqlite.execSQL(
            "INSERT INTO messages VALUES "
                + "('m1','c1','USER',1,'旧消息','COMPLETED',NULL,NULL,1,1)"
        );
        sqlite.execSQL(
            "INSERT INTO memories VALUES "
                + "('memory-legacy','companion-linyu','FACT','喜欢安静',3,1,1)"
        );
        sqlite.setVersion(4);
        sqlite.close();
    }
}
