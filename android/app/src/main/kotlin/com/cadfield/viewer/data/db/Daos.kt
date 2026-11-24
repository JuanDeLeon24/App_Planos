package com.cadfield.viewer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(p: ProjectEntity)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): ProjectEntity?
}

@Dao
interface DocumentDao {
    @Query(
        """SELECT * FROM documents
           WHERE (:projectId IS NULL OR projectId = :projectId)
             AND (:discipline IS NULL OR discipline = :discipline)
             AND (:front IS NULL OR front = :front)
             AND (:query = '' OR displayName LIKE '%' || :query || '%' OR tunnel LIKE '%' || :query || '%')
           ORDER BY updatedAt DESC"""
    )
    fun observe(projectId: String?, discipline: String?, front: String?, query: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE originalHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<DocumentEntity>

    @Query("SELECT DISTINCT discipline FROM documents WHERE discipline IS NOT NULL ORDER BY discipline")
    fun observeDisciplines(): Flow<List<String>>

    @Insert suspend fun insert(d: DocumentEntity)
    @Update suspend fun update(d: DocumentEntity)
    @Update suspend fun updateAll(d: List<DocumentEntity>)
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTag(t: TagEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun tag(dt: DocumentTagEntity)
    @Query("DELETE FROM document_tags WHERE documentId = :doc AND tag = :tag") suspend fun untag(doc: String, tag: String)
    @Query("SELECT tag FROM document_tags WHERE documentId = :doc") fun observeTags(doc: String): Flow<List<String>>
}

@Dao
interface VersionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(v: VersionEntity)
    @Query("SELECT * FROM versions WHERE documentId = :doc ORDER BY timestamp DESC")
    fun observe(doc: String): Flow<List<VersionEntity>>
}

@Dao
interface SyncQueueDao {
    @Insert suspend fun enqueue(item: SyncQueueEntity)
    @Query("SELECT * FROM sync_queue ORDER BY id LIMIT :limit") suspend fun peek(limit: Int): List<SyncQueueEntity>
    @Query("DELETE FROM sync_queue WHERE id = :id") suspend fun done(id: Long)
    @Update suspend fun update(item: SyncQueueEntity)
    @Query("SELECT COUNT(*) FROM sync_queue") fun observeCount(): Flow<Int>
}

@Dao
interface AuditDao {
    @Insert suspend fun log(e: AuditEntity)
    @Query("SELECT * FROM audit_log WHERE documentId = :doc ORDER BY timestamp DESC")
    fun observe(doc: String): Flow<List<AuditEntity>>
}
