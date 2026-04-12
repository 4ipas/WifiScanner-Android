package com.example.wifiscanner.utils

import android.content.Context
import com.example.wifiscanner.models.NodeDTO
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Персистентное хранение задач во внутреннем хранилище приложения (Context.filesDir).
 */
object TaskPersistence {
    private const val TASKS_FILE = "active_tasks.json"
    private const val CSV_MAP_FILE = "entrance_csv_map.json"

    private val gson = Gson()

    fun saveTasks(context: Context, tasks: List<NodeDTO>) {
        val file = File(context.filesDir, TASKS_FILE)
        if (tasks.isEmpty()) {
            file.delete()
            return
        }
        file.writeText(gson.toJson(tasks))
    }

    fun loadTasks(context: Context): MutableList<NodeDTO> {
        val file = File(context.filesDir, TASKS_FILE)
        if (!file.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<NodeDTO>>() {}.type
            gson.fromJson(file.readText(), type) ?: mutableListOf()
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    fun saveCsvMap(context: Context, map: Map<String, String>) {
        val file = File(context.filesDir, CSV_MAP_FILE)
        file.writeText(gson.toJson(map))
    }

    fun loadCsvMap(context: Context): MutableMap<String, String> {
        val file = File(context.filesDir, CSV_MAP_FILE)
        if (!file.exists()) return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, String>>() {}.type
            gson.fromJson(file.readText(), type) ?: mutableMapOf()
        } catch (e: Exception) {
            e.printStackTrace()
            mutableMapOf()
        }
    }
}
