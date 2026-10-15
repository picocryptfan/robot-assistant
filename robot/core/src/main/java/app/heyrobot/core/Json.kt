// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.json.JSONArray
import org.json.JSONObject

// Pure (org.json). Loosely typed values (tool arguments and results) to and from JSON.

object Json {
    fun toMap(o: JSONObject): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        for (k in o.keys()) m[k] = fromJson(o.opt(k))
        return m
    }

    fun toList(a: JSONArray): List<Any?> = (0 until a.length()).map { fromJson(a.opt(it)) }

    fun fromJson(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> toMap(v)
        is JSONArray -> toList(v)
        else -> v
    }

    fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { for ((k, x) in v) put(k.toString(), toJson(x)) }
        is List<*> -> JSONArray().apply { v.forEach { put(toJson(it)) } }
        is Boolean, is Int, is Long, is Double, is String -> v
        is Float -> v.toDouble()
        is Number -> v.toDouble()
        is Enum<*> -> v.name
        else -> v.toString()
    }

    fun obj(m: Map<String, Any?>): JSONObject = toJson(m) as JSONObject
}
