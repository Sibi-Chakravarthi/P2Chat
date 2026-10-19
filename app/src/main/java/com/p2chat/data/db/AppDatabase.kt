package com.p2chat.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.p2chat.data.ai.BlockedVectorDao
import com.p2chat.data.ai.BlockedVectorEntity
import com.p2chat.data.ai.QuarantineDao
import com.p2chat.data.ai.QuarantineEntity
import com.p2chat.data.bench.BenchDao
import com.p2chat.data.bench.BenchRunEntity
import com.p2chat.data.bench.BenchSampleEntity
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.GroupEntity
import com.p2chat.data.group.RosterMember
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.data.identity.LocalIdentity
import com.p2chat.data.identity.LocalIdentityDao
import com.p2chat.data.identity.Peer
import com.p2chat.data.identity.PeerDao
import com.p2chat.data.message.LocalAtRestCipher
import com.p2chat.data.message.MessageDao
import com.p2chat.data.message.MessageEntity
import com.p2chat.data.mesh.BufferedPacket
import com.p2chat.data.mesh.BufferedPacketDao
import com.p2chat.data.mesh.SeenMessage
import com.p2chat.data.mesh.SeenMessageDao
import com.p2chat.data.settings.Setting
import com.p2chat.data.settings.SettingDao

/**
 * P2Chat primary Room database (Decision 10, PRD §4).
 * Single local encrypted database storing mesh deduplication, packet buffer,
 * identities, groups, roster, messages, quarantine, user blocked vectors, and benchmark runs.
 */
@Database(
    entities = [
        SeenMessage::class,
        BufferedPacket::class,
        LocalIdentity::class,
        Peer::class,
        GroupEntity::class,
        RosterMember::class,
        Setting::class,
        MessageEntity::class,
        QuarantineEntity::class,
        BlockedVectorEntity::class,
        BenchRunEntity::class,
        BenchSampleEntity::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(LocalAtRestCipher::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun seenMessageDao(): SeenMessageDao
    abstract fun bufferedPacketDao(): BufferedPacketDao
    abstract fun localIdentityDao(): LocalIdentityDao
    abstract fun peerDao(): PeerDao
    abstract fun groupDao(): GroupDao
    abstract fun rosterMemberDao(): RosterMemberDao
    abstract fun settingDao(): SettingDao
    abstract fun messageDao(): MessageDao
    abstract fun quarantineDao(): QuarantineDao
    abstract fun blockedVectorDao(): BlockedVectorDao
    abstract fun benchDao(): BenchDao

    companion object {
        const val DATABASE_NAME = "p2chat.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_identity` (
                        `sender_fp` INTEGER NOT NULL,
                        `alias` TEXT NOT NULL,
                        `signing_pub` BLOB NOT NULL,
                        `ecdh_pub` BLOB NOT NULL,
                        `wrapped_signing_priv` BLOB NOT NULL,
                        `wrapped_ecdh_priv` BLOB NOT NULL,
                        PRIMARY KEY(`sender_fp`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `peers` (
                        `sender_fp` INTEGER NOT NULL,
                        `signing_pub` BLOB NOT NULL,
                        `ecdh_pub` BLOB NOT NULL,
                        `alias` TEXT NOT NULL,
                        `verified` INTEGER NOT NULL,
                        `first_seen_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`sender_fp`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `groups` (
                        `group_id` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `my_role` TEXT NOT NULL,
                        `current_epoch` INTEGER NOT NULL,
                        `wrapped_current_key` BLOB NOT NULL,
                        `prev_epoch` INTEGER,
                        `wrapped_prev_key` BLOB,
                        `grace_expires_ms` INTEGER,
                        `roster_version` INTEGER NOT NULL,
                        PRIMARY KEY(`group_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `roster_members` (
                        `group_id` INTEGER NOT NULL,
                        `sender_fp` INTEGER NOT NULL,
                        `signing_pub` BLOB NOT NULL,
                        `ecdh_pub` BLOB NOT NULL,
                        `alias` TEXT NOT NULL,
                        `revoked` INTEGER NOT NULL,
                        PRIMARY KEY(`group_id`, `sender_fp`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `settings` (
                        `key` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `messages` (
                        `msg_id` INTEGER NOT NULL,
                        `group_id` INTEGER NOT NULL,
                        `sender_fp` INTEGER NOT NULL,
                        `key_epoch` INTEGER NOT NULL,
                        `sent_at_ms` INTEGER NOT NULL,
                        `received_at_ms` INTEGER NOT NULL,
                        `body` BLOB NOT NULL,
                        `sig_ok` INTEGER NOT NULL,
                        `tier` INTEGER NOT NULL,
                        `score` REAL NOT NULL,
                        `revealed` INTEGER NOT NULL,
                        PRIMARY KEY(`msg_id`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `quarantine` (
                        `msg_id` INTEGER NOT NULL,
                        `group_id` INTEGER NOT NULL,
                        `sender_fp` INTEGER NOT NULL,
                        `body` BLOB NOT NULL,
                        `score` REAL NOT NULL,
                        `nearest_category` TEXT NOT NULL,
                        `created_at_ms` INTEGER NOT NULL,
                        `expires_at_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`msg_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_blocked_vectors` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `vector` BLOB NOT NULL,
                        `force_quarantine` INTEGER NOT NULL,
                        `source_msg_id` INTEGER NOT NULL,
                        `created_at_ms` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bench_runs` (
                        `run_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `started_at_ms` INTEGER NOT NULL,
                        `finished_at_ms` INTEGER NOT NULL,
                        `burst_count` INTEGER NOT NULL,
                        `interval_ms` INTEGER NOT NULL,
                        `payload_bytes` INTEGER NOT NULL,
                        `initial_ttl` INTEGER NOT NULL,
                        `connected_peers` INTEGER NOT NULL,
                        `pdr_percent` REAL NOT NULL,
                        `rtt_half_p50_ms` REAL NOT NULL,
                        `rtt_half_p95_ms` REAL NOT NULL,
                        `throughput_bps` REAL NOT NULL,
                        `inference_p50_ms` REAL NOT NULL,
                        `inference_p95_ms` REAL NOT NULL,
                        `device_models` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bench_samples` (
                        `run_id` INTEGER NOT NULL,
                        `msg_id` INTEGER NOT NULL,
                        `seq_num` INTEGER NOT NULL,
                        `initial_ttl` INTEGER NOT NULL,
                        `received_ttl` INTEGER NOT NULL,
                        `hop_count` INTEGER NOT NULL,
                        `sent_mono_ns` INTEGER NOT NULL,
                        `ack_mono_ns` INTEGER NOT NULL,
                        `rtt_half_ms` REAL NOT NULL,
                        `delivered` INTEGER NOT NULL,
                        PRIMARY KEY(`run_id`, `msg_id`),
                        FOREIGN KEY(`run_id`) REFERENCES `bench_runs`(`run_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bench_samples_run_id` ON `bench_samples` (`run_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bench_samples_run_id_delivered` ON `bench_samples` (`run_id`, `delivered`)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun createInMemory(context: Context): AppDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AppDatabase::class.java
            ).allowMainThreadQueries().build()
        }
    }
}
