package com.example.studentlookup.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.studentlookup.data.model.Student

@Dao
interface StudentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<Student>)

    @Query("DELETE FROM students")
    suspend fun clear()

    @Query("SELECT * FROM students")
    suspend fun getAll(): List<Student>

    @Query("SELECT DISTINCT term FROM students ORDER BY term")
    suspend fun getTerms(): List<String>

    @Query("SELECT COUNT(*) FROM students")
    suspend fun count(): Int

    @Query("SELECT COUNT(DISTINCT name) FROM students")
    suspend fun countStudents(): Int
}
