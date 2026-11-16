package com.premium.sipdialer.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM sip_accounts ORDER BY accountName ASC")
    fun observeSipAccounts(): Flow<List<SipAccountEntity>>

    @Query("SELECT * FROM sip_accounts")
    suspend fun getAllSipAccounts(): List<SipAccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSipAccount(account: SipAccountEntity)

    @Delete
    suspend fun deleteSipAccount(account: SipAccountEntity)

    @Query("UPDATE sip_accounts SET isDefaultOutgoing = (accountId = :selectedId)")
    suspend fun setDefaultOutgoingAccount(selectedId: String)

    @Query("SELECT * FROM call_logs ORDER BY startTimeMillis DESC")
    fun observeCallLogs(): Flow<List<CallRecordEntity>>

    @Query("SELECT * FROM call_logs ORDER BY startTimeMillis DESC")
    suspend fun getAllCallLogs(): List<CallRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLog(record: CallRecordEntity): Long

    @Query("DELETE FROM call_logs WHERE id = :id")
    suspend fun deleteCallLog(id: Long)

    @Query("SELECT * FROM blocked_numbers")
    fun observeBlockedNumbers(): Flow<List<BlockedNumberEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE number = :num AND isWhitelist = 0)")
    suspend fun isNumberBlocked(num: String): Boolean
}

@Database(
    entities = [SipAccountEntity::class, CallRecordEntity::class, BlockedNumberEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "premium_sip.db")
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
