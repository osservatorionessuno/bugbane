package org.osservatorionessuno.qf

import org.json.JSONArray
import org.json.JSONObject

class Utils {
    companion object {
        fun toJsonString(data: Any): String {
            if (data is JSONObject) {
                return data.toString(1).replace("\\/", "/")
            }
            if (data is JSONArray) {
                return data.toString(1).replace("\\/", "/")
            }
            return data.toString()
        }
    }
}