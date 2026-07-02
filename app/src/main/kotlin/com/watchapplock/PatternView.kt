package com.watchapplock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 极简 3x3 图案锁视图（规格 §4：图案输入）。
 *
 * - 9 个节点，按下并滑动串联，松手回调结果
 * - 仅内存绘制，无图片资源，贴合低性能手表
 */
class PatternView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val nodes = ArrayList<Node>()
    private val selected = ArrayList<Int>()
    private val path = Path()

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33FFFFFF
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCCFFFFFF.toInt()
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var lastX = 0f
    private var lastY = 0f
    var onPatternComplete: ((List<Int>) -> Unit)? = null

    private data class Node(var x: Float, var y: Float, val index: Int)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        nodes.clear()
        val side = minOf(w, h).toFloat()
        val gap = side / 4f
        val startX = (w - 2 * gap) / 2f
        val startY = (h - 2 * gap) / 2f
        var idx = 0
        for (r in 0..2) for (c in 0..2) {
            nodes.add(Node(startX + c * gap, startY + r * gap, idx++))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (n in nodes) {
            val p = if (selected.contains(n.index)) activePaint else dotPaint
            canvas.drawCircle(n.x, n.y, dp(9f), p)
        }
        if (selected.isNotEmpty()) {
            path.reset()
            val first = nodes[selected[0]]
            path.moveTo(first.x, first.y)
            for (i in 1 until selected.size) {
                val n = nodes[selected[i]]
                path.lineTo(n.x, n.y)
            }
            if (selected.size < nodes.size) {
                path.lineTo(lastX, lastY)
            }
            canvas.drawPath(path, linePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                clear()
                hitTest(event.x, event.y)
                lastX = event.x; lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = event.x; lastY = event.y
                hitTest(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                lastX = event.x; lastY = event.y
                invalidate()
                if (selected.isNotEmpty()) {
                    onPatternComplete?.invoke(ArrayList(selected))
                }
                Handler(Looper.getMainLooper()).postDelayed({ clear(); invalidate() }, 400)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float) {
        val r = dp(16f)
        for (n in nodes) {
            if (selected.contains(n.index)) continue
            if (Math.abs(x - n.x) <= r && Math.abs(y - n.y) <= r) {
                selected.add(n.index)
            }
        }
    }

    fun clear() { selected.clear(); path.reset() }

    private fun dp(v: Float): Float =
        v * resources.displayMetrics.density
}
