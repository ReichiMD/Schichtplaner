package de.reichimd.schichtplaner.core.storage

/** Eine gespeicherte Datei der App ist unlesbar oder hat ein ungültiges Format. */
open class DataFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Die Datei ist in Ordnung, aber von einer neueren App-Version geschrieben. Nicht anfassen. */
class DataFileTooNewException(val version: Int, what: String) :
    DataFileException("$what stammt aus einer neueren App-Version ($version)")
