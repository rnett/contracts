package com.rnett.ligraph.eve.contracts

import com.rnett.core.PooledDBConnection
import org.jetbrains.exposed.sql.Database
import java.io.File

object DBConnection {
    fun connect() {
        Database.connect(PooledDBConnection.connect(File("./DB_Connection.txt").readText()))
    }
}