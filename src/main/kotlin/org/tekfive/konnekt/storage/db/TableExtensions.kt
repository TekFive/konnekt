package org.tekfive.konnekt.storage.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

fun Table.storageBucketId(name: String = "storage_bucket_id", unique: Boolean = true): Column<String> {
    val column = varchar(name, 50)
    if (unique) {
        column.uniqueIndex("${tableName}_${name}_uq")
    }
    return column
}