package dev.duma.android.hal.contract

import android.os.Parcel
import android.os.Parcelable

sealed class CommandResult : Parcelable {
    data class Success(val body: String? = null) : CommandResult() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(0) // type tag
            parcel.writeString(body)
        }

        override fun describeContents(): Int = 0
    }

    data class Failure(
        val code: String,
        val message: String,
        val type: ErrorType = ErrorType.INTERNAL
    ) : CommandResult() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(1) // type tag
            parcel.writeString(code)
            parcel.writeString(message)
            parcel.writeInt(type.ordinal)
        }

        override fun describeContents(): Int = 0
    }

    enum class ErrorType {
        BAD_REQUEST,
        UNAUTHORIZED,
        FORBIDDEN,
        NOT_FOUND,
        TIMEOUT,
        INTERNAL,
        UNAVAILABLE
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<CommandResult> = object : Parcelable.Creator<CommandResult> {
            override fun createFromParcel(parcel: Parcel): CommandResult {
                return when (parcel.readInt()) {
                    0 -> Success(parcel.readString())
                    1 -> {
                        val code = parcel.readString() ?: ""
                        val message = parcel.readString() ?: ""
                        val type = ErrorType.entries[parcel.readInt()]
                        Failure(code, message, type)
                    }
                    else -> throw IllegalArgumentException("Unknown CommandResult type")
                }
            }

            override fun newArray(size: Int): Array<CommandResult?> = arrayOfNulls(size)
        }

        fun badRequest(message: String) =
            Failure("bad_request", message, ErrorType.BAD_REQUEST)
        fun unauthorized(message: String = "Unauthorized") =
            Failure("unauthorized", message, ErrorType.UNAUTHORIZED)
        fun forbidden(message: String) =
            Failure("forbidden", message, ErrorType.FORBIDDEN)
        fun notFound(message: String) =
            Failure("not_found", message, ErrorType.NOT_FOUND)
        fun timeout(message: String) =
            Failure("timeout", message, ErrorType.TIMEOUT)
        fun internalError(message: String) =
            Failure("internal_error", message, ErrorType.INTERNAL)
        fun unavailable(message: String) =
            Failure("unavailable", message, ErrorType.UNAVAILABLE)
        fun unsupportedMethod(method: String) =
            Failure("unsupported_method", "Method not supported: $method", ErrorType.NOT_FOUND)
    }
}
