package com.medo.gamebot

import android.content.Context
import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("gamebot", Context.MODE_PRIVATE)

    fun game(c: Context): String = sp(c).getString("game", "") ?: ""
    fun setGame(c: Context, pkg: String) { sp(c).edit().putString("game", pkg).apply() }
    fun gain(c: Context): Float = sp(c).getFloat("gain", 1.0f)
    fun setGain(c: Context, v: Float) { sp(c).edit().putFloat("gain", v).apply() }

    fun buttons(c: Context): MutableMap<String, PointF> {
        val out = HashMap<String, PointF>()
        try {
            val o = JSONObject(sp(c).getString("buttons", "{}") ?: "{}")
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                out[k] = PointF(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
            }
        } catch (e: Exception) { /* ignore corrupt data */ }
        return out
    }

    fun saveButtons(c: Context, m: Map<String, PointF>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, JSONArray().put(v.x.toDouble()).put(v.y.toDouble()))
        sp(c).edit().putString("buttons", o.toString()).apply()
    }

    fun protos(c: Context, enemy: Boolean): MutableList<FloatArray> {
        val out = ArrayList<FloatArray>()
        try {
            val arr = JSONArray(sp(c).getString(if (enemy) "enemy" else "friend", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                out.add(FloatArray(a.length()) { j -> a.getDouble(j).toFloat() })
            }
        } catch (e: Exception) { /* ignore */ }
        return out
    }

    fun saveProtos(c: Context, enemy: Boolean, list: List<FloatArray>) {
        val arr = JSONArray()
        for (p in list) { val a = JSONArray(); for (v in p) a.put(v.toDouble()); arr.put(a) }
        sp(c).edit().putString(if (enemy) "enemy" else "friend", arr.toString()).apply()
    }
}
