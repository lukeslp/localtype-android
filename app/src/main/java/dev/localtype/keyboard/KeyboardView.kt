package dev.localtype.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.graphics.Matrix
import android.graphics.Path
import androidx.core.graphics.PathParser
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.abs

/**
 * Draws the keys and turns touches into [KeyAction]s. Each key's touch area is its whole cell,
 * including the visual gap, so near misses still land on the closest key.
 */
class KeyboardView(context: Context) : View(context) {
    interface Listener {
        fun onKey(action: KeyAction)
        fun onLongPressSpace()

        /**
         * Where a finger lifted off [key], just before its [onKey]: [dx] key widths right of the
         * key's center and [dy] rows below it. Spelling correction uses it to tell near misses apart.
         */
        fun onKeyTouch(key: Key, dx: Float, dy: Float) {}

        /** TalkBack's hold action on Shift: turns caps lock on, or off when it is already on. */
        fun onCapsLock() {}

        /** Whether a press on [action]'s key (space or delete) may turn into a slide right now. */
        fun slideAllowed(action: KeyAction): Boolean = false

        /** A slide along the space bar moved [delta] characters further: below zero is left. */
        fun onCursorSlide(delta: Int) {}

        /** A slide left from delete now covers [words] whole words; nothing is removed until it ends. */
        fun onDeleteSlide(words: Int) {}

        /** The delete slide ended: remove what it covered when [commit], otherwise leave the text alone. */
        fun onDeleteSlideEnd(commit: Boolean) {}
        /** Whether a press on a letter may become a swipe ([SwipeStart.Accepted]) or, refused, why. */
        fun onSwipeStart(): SwipeStart = SwipeStart.Off
        fun onSwipe(path: SwipePath) {}
        fun onSwipeCancel() {}

        /** A press refused at its start went on to travel like a swipe: it typed nothing, and [reason] says why. */
        fun onSwipeRefused(reason: SwipeRefusal) {}

        /** Whether a second finger landing now may be the start of a two-finger swipe ([GestureRules.watches]). */
        fun twoFingerAllowed(): Boolean = false

        /** A two-finger swipe was recognized, or TalkBack chose its action on the space key. */
        fun onTwoFingerSwipe(swipe: TwoFingerSwipe) {}

        /** The swipes TalkBack gets as actions on the space key, each with its action's name. */
        fun twoFingerActions(): List<Pair<TwoFingerSwipe, String>> = emptyList()

        /** Holding Space opens a language chooser (more than one language is on), so TalkBack gets it as the hold action. */
        fun languageChooserOffered(): Boolean = false
    }

    var listener: Listener? = null

    var layout: KeyboardLayout = KeyboardLayout(emptyList())
        set(value) {
            field = value
            cancelAllPointers()
            requestLayout()
            computeKeyBounds()
            invalidate()
            accessibility.invalidateRoot()
        }

    var shiftState: ShiftState = ShiftState.OFF
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            accessibility.invalidateRoot()
        }

    var enterLabel: String = "↵"
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            accessibility.invalidateRoot()
        }

    /** How far a slide on space or delete travels per caret step or word. */
    var slideSpeed: CursorGestures.Speed = CursorGestures.Speed.STANDARD

    /**
     * False draws letter and other text keys as their labels on the keyboard's background, with no shape behind
     * them until pressed. Shift, Delete, Return, Space and the other function keys keep theirs.
     */
    var keyShapes: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Ignore the narrow painted row gaps, plus a band below the suggestion strip; off by default. */
    var inactiveGaps: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            cancelAllPointers()
            requestLayout()
            computeKeyBounds()
            invalidate()
            accessibility.invalidateRoot()
        }

    private val stripGap: Float get() = if (inactiveGaps) InactiveGaps.BELOW_STRIP_DP * density else 0f

    /** Touch accommodations for a tremor or limited reach ([MotorSettings]), read once per field. */
    var motor: MotorSettings = MotorSettings.OFF
        set(value) {
            if (field == value) return
            field = value
            cancelAllPointers()
        }

    /** A letter key's learned mean lift, for [MotorSettings.aimFromTouches]; null while it has too few to aim by. */
    var aimShift: (Key) -> AimShift? = { null }

    /** When the last finger lifted, on the events' clock, for [MotorSettings.ignoreRepeatMs]. */
    private var lastLift: Long? = null

    /** Touches ignored as repeats or second fingers: everything they do is ignored until they lift. */
    private val ignored = HashSet<Int>()

    enum class ShiftState { OFF, ON, LOCKED }

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val handler = Handler(Looper.getMainLooper())

    private val keyColor = context.getColor(R.color.kb_key)
    private val keyPressedColor = context.getColor(R.color.kb_key_pressed)
    private val modifierColor = context.getColor(R.color.kb_modifier)
    private val modifierPressedColor = context.getColor(R.color.kb_modifier_pressed)
    private val accentColor = context.getColor(R.color.kb_accent)
    private val accentPressedColor = context.getColor(R.color.kb_accent_pressed)
    private val onKeyColor = context.getColor(R.color.kb_on_key)
    private val onAccentColor = context.getColor(R.color.kb_on_accent)
    private val hintColor = context.getColor(R.color.kb_hint)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = 11f * scaled
        color = hintColor
    }

    private val fullRowHeight = 54f * density
    /** Full height, unless the window offers less room than the keys need; see onMeasure. */
    private var rowHeight = fullRowHeight
    private val gapX = 3f * density
    private val gapY = 5f * density
    private val radius = 9f * density
    private val sidePadding = 3f * density
    private val keyRect = RectF()

    /** Hit rectangles, one per key, parallel to [layout] rows. */
    private var bounds: List<List<RectF>> = emptyList()

    private data class Pointer(
        var key: Key,
        var x: Float,
        var y: Float,
        var longPressed: Boolean = false,
        var popup: AlternatesPopup? = null,
        /** Where the finger came down, which a slide on space or delete is measured from. */
        val downX: Float = x,
        val downY: Float = y,
        /** The key it came down on: only a press that began on space or delete can become a slide. */
        val downAction: KeyAction = key.action,
        var sliding: Boolean = false,
        /** Caret steps or words the slide stood for when the listener last heard. */
        var slideSteps: Int = 0,
        var slideReported: Boolean = false,
        var gesture: SwipeGesture? = null,
        var gestureStart: Long = 0,
        /**
         * Followed beside a press whose swipe was refused, so a swipe can be told from a tap; until it
         * travels like one the press is an ordinary tap or hold.
         */
        var refused: SwipeGesture? = null,
        var refusal: SwipeRefusal? = null,
        /** When the finger came down, on the events' clock. */
        val downTime: Long = 0,
        /** One of a two-finger pair that turned into a swipe: it types nothing and shows no key pressed. */
        var consumed: Boolean = false,
        /** When the finger reached [key], from which a hold time or dwell is measured. */
        var keySince: Long = downTime,
        /** A hold time has passed on [key]: lifting now types it. */
        var ready: Boolean = false,
        /** Resting typed [key] already: it types again only after the finger leaves it or lifts. */
        var dwelled: Boolean = false,
        /** The pending hold-time or dwell timer for [key]. */
        var timer: Runnable? = null,
        /** A normal press crossed an inactive band; its hold/dwell must start anew on returning. */
        var inGap: Boolean = false,
    )
    private val pointers = HashMap<Int, Pointer>()
    /** A first finger held back, not yet typed, and the second finger that may make a swipe with it. */
    private var pair: TwoFingerPair? = null
    private var pairsStarted = 0
    private var pairsActive = 0
    private var pairsRecognized = 0

    /** Counts only, for dumpsys: pairs that held a key back, that became swipes in progress, and that were recognized. */
    val twoFingerCounts: String get() = "pairs=$pairsStarted active=$pairsActive recognized=$pairsRecognized"
    private var longPressRunnable: Runnable? = null
    private var repeatRunnable: Runnable? = null

    private val accessibility = KeyAccessibilityHelper()

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        isHapticFeedbackEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = layout.rowHeights.sum()
        val desired = (rows * fullRowHeight + gapY + stripGap).toInt() + paddingTop + paddingBottom
        val height = resolveSize(desired, heightMeasureSpec)
        // With less room than the keys need, rows get shorter instead of the bottom row being clipped off the screen.
        val fitted = if (height < desired && rows > 0) ((height - paddingTop - paddingBottom - gapY - stripGap) / rows).coerceAtLeast(1f) else fullRowHeight
        if (fitted != rowHeight) {
            rowHeight = fitted
            computeKeyBounds()
        }
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeKeyBounds()
    }

    private fun computeKeyBounds() {
        if (width == 0) return
        val usable = width - sidePadding * 2
        var top = paddingTop + gapY / 2 + stripGap
        bounds = layout.rows.mapIndexed { rowIndex, row ->
            val unit = usable / maxOf(KeyboardLayout.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat())
            val rowWidth = row.sumOf { it.width.toDouble() }.toFloat() * unit
            var x = sidePadding + (usable - rowWidth) / 2
            val height = rowHeight * layout.rowHeights.getOrElse(rowIndex) { 1f }
            val cells = row.map { key ->
                RectF(x, top, x + key.width * unit, top + height).also { x += key.width * unit }
            }
            top += height
            cells
        }
    }

    override fun onDraw(canvas: Canvas) {
        layout.rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { keyIndex, key ->
                if (key.isSpacer) return@forEachIndexed
                val cell = bounds.getOrNull(rowIndex)?.getOrNull(keyIndex) ?: return@forEachIndexed
                drawKey(canvas, key, cell, pressed = pointers.values.any { it.key === key && !it.consumed })
            }
        }
        if (motor.timed) drawTiming(canvas)
        if (pointers.values.none { it.gesture?.active == true }) return
        val origin = letterOrigin() ?: return
        trailPaint.strokeWidth = 3f * density
        pointers.values.mapNotNull { it.gesture?.takeIf { g -> g.active && !g.cancelled } }.forEach { gesture ->
            val points = gesture.points
            val from = SwipeTrail.start(points, gesture.aspect)
            val count = points.size - 1 - from
            for (i in from until points.size - 1) {
                val a = points[i]; val b = points[i + 1]
                // The newest stretch only, fading out toward its older end.
                trailPaint.color = (onKeyColor and 0x00ffffff) or ((0xb4 * (i - from + 1) / count) shl 24)
                canvas.drawLine(origin.left + a.x * origin.width(), origin.top + a.y * origin.height(),
                    origin.left + b.x * origin.width(), origin.top + b.y * origin.height(), trailPaint)
            }
        }
    }

    /**
     * A bar along the bottom of a key with a hold time or dwell running, filling until the key is ready (hold) or
     * typed (dwell), so the person can see how long is left before lifting or moving on.
     */
    private fun drawTiming(canvas: Canvas) {
        val ms = if (motor.dwell) motor.dwellMs else motor.holdMs
        val now = SystemClock.uptimeMillis()
        var pending = false
        pointers.values.forEach { pointer ->
            if (pointer.consumed || pointer.sliding || pointer.longPressed || pointer.popup != null || pointer.gesture?.active == true) return@forEach
            val cell = keyBounds(pointer.key) ?: return@forEach
            val done = pointer.ready || pointer.dwelled
            val progress = if (done) 1f else MotorRules.progress(pointer.keySince, now, ms)
            if (!done && pointer.timer != null) pending = true
            val left = cell.left + gapX / 2 + radius / 2
            val right = cell.right - gapX / 2 - radius / 2
            keyRect.set(left, cell.bottom - gapY / 2 - 5f * density, left + (right - left) * progress, cell.bottom - gapY / 2 - 1f * density)
            fill.color = accentColor
            canvas.drawRoundRect(keyRect, 2f * density, 2f * density, fill)
        }
        if (pending) postInvalidateOnAnimation()
    }

    private fun letterOrigin(): RectF? = layout.rows.withIndex().firstNotNullOfOrNull { (row, keys) ->
        keys.indexOfFirst { (it.action as? KeyAction.Text)?.value == "q" }.takeIf { it >= 0 }?.let { bounds.getOrNull(row)?.getOrNull(it) }
    }

    private fun sampleGesture(pointer: Pointer, event: MotionEvent, index: Int, gesture: SwipeGesture? = pointer.gesture) {
        gesture ?: return
        val cell = letterOrigin() ?: return
        for (h in 0 until event.historySize) gesture.add((event.getHistoricalX(index, h) - cell.left) / cell.width(),
            (event.getHistoricalY(index, h) - cell.top) / cell.height(), (event.getHistoricalEventTime(h) - pointer.gestureStart).toFloat())
        gesture.add((event.getX(index) - cell.left) / cell.width(), (event.getY(index) - cell.top) / cell.height(),
            (event.eventTime - pointer.gestureStart).toFloat())
    }

    private fun drawKey(canvas: Canvas, key: Key, cell: RectF, pressed: Boolean) {
        keyRect.set(cell.left + gapX / 2, cell.top + gapY / 2, cell.right - gapX / 2, cell.bottom - gapY / 2)
        val shiftActive = key.action == KeyAction.Shift && shiftState != ShiftState.OFF
        fill.color = when {
            key.style == KeyStyle.ACCENT || shiftActive -> if (pressed) accentPressedColor else accentColor
            key.style == KeyStyle.MODIFIER -> if (pressed) modifierPressedColor else modifierColor
            else -> if (pressed) keyPressedColor else keyColor
        }
        if (TypingRules.drawsKeyShape(keyShapes, pressed, key.style)) canvas.drawRoundRect(keyRect, radius, radius, fill)

        labelPaint.color = if (key.style == KeyStyle.ACCENT || shiftActive) onAccentColor else onKeyColor
        iconFor(key)?.let { icon ->
            drawIcon(canvas, icon, keyRect, labelPaint.color)
            return
        }
        val label = displayLabel(key)
        labelPaint.textSize = when {
            label.length > 3 -> 14f * scaled
            label.length == 1 && !label[0].isLetterOrDigit() && key.action !is KeyAction.Text -> 24f * scaled
            key.style == KeyStyle.MODIFIER || key.style == KeyStyle.ACCENT -> 16f * scaled
            else -> 22f * scaled
        }
        val baseline = keyRect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2
        canvas.drawText(label, keyRect.centerX(), baseline, labelPaint)

        // Common-symbol holds are hinted on letters too. Dedicated number-row keys stay clean.
        key.holdHint?.let {
            canvas.drawText(it, keyRect.right - 5f * density, keyRect.top + 13f * density, hintPaint)
        }
    }

    /** Material icon paths on a 24-unit grid, drawn instead of font glyphs that render thin. */
    private fun iconFor(key: Key): Path? = when (key.action) {
        KeyAction.Shift -> icons.getValue(
            when (shiftState) {
                ShiftState.OFF -> SHIFT
                ShiftState.ON -> SHIFT_ON
                ShiftState.LOCKED -> SHIFT_LOCKED
            },
        )
        KeyAction.Backspace -> icons.getValue(BACKSPACE)
        KeyAction.Voice -> icons.getValue(MIC)
        KeyAction.Enter -> icons[enterLabel] ?: icons.getValue(RETURN).takeIf { enterLabel == "↵" }
        else -> null
    }

    private val iconMatrix = Matrix()
    private val scaledIcon = Path()

    private fun drawIcon(canvas: Canvas, icon: Path, rect: RectF, color: Int) {
        val size = 24f * density
        iconMatrix.setScale(size / 24f, size / 24f)
        iconMatrix.postTranslate(rect.centerX() - size / 2, rect.centerY() - size / 2)
        icon.transform(iconMatrix, scaledIcon)
        fill.color = color
        canvas.drawPath(scaledIcon, fill)
    }

    private fun displayLabel(key: Key): String = when (key.action) {
        KeyAction.Enter -> enterLabel
        is KeyAction.Text -> if (shiftState != ShiftState.OFF && key.label.length == 1) key.label.uppercase() else key.label
        else -> key.label
    }

    // Keys are virtual; TalkBack clicks go through KeyAccessibilityHelper instead.
    override fun performClick(): Boolean = super.performClick()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val id = event.getPointerId(index)
        // A second/third DOWN or another finger's UP can force an earlier press to commit.
        // Every contact in the event carries a newer position, even without a MOVE of its own.
        for (i in 0 until event.pointerCount) pointers[event.getPointerId(i)]?.let { pointer ->
            pointer.x = event.getX(i); pointer.y = event.getY(i)
            if (inactiveGaps && !pointer.consumed && !pointer.longPressed && !pointer.sliding && pointer.popup == null &&
                pointer.gesture?.let { it.active || it.cancelled } != true && keyAt(pointer.x, pointer.y) == null) {
                if (!pointer.inGap && pair?.first != event.getPointerId(i)) cancelTimers()
                pointer.timer?.let(handler::removeCallbacks)
                pointer.timer = null
                pointer.ready = false
                pointer.inGap = true
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) ignored.clear()
                // A bounce or a brush: a touch too soon after the last lift, or a second finger while one is down.
                val second = event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && motor.oneFinger && pointers.isNotEmpty()
                if (second || MotorRules.ignoresRepeat(motor.ignoreRepeatMs, lastLift, event.eventTime)) {
                    ignored.add(id)
                    return true
                }
                val key = aimedKeyAt(event.getX(index), event.getY(index))
                if (key == null) {
                    // A gap-down never acquires a key later, including while dwell can adopt a moving finger.
                    ignored.add(id)
                    return true
                }
                val first = pointers.entries.singleOrNull()
                val unit = keyWidth()
                if (pair == null && key != null && first != null && event.pointerCount == 2 && unit > 0f && pairable(first.value, key) &&
                    GestureClassifier.pairs(event.eventTime - first.value.downTime, (event.getX(index) - first.value.x) / unit,
                        (event.getY(index) - first.value.y) / unit) && listener?.twoFingerAllowed() == true) {
                    // Two fingers that may be a swipe: the first key is held back, where and as it is, until they are told apart.
                    pair = TwoFingerPair(first.key, first.value.x / unit, first.value.y / unit,
                        id, event.getX(index) / unit, event.getY(index) / unit, event.eventTime)
                    pairsStarted++
                } else {
                    // A third touch ends a pair: one still undecided was typing after all, its held key first; a swipe
                    // in progress is abandoned, its fingers consumed.
                    pair?.let { pair = null; releasePointer(it.first, commit = true) }
                    // Fast two-thumb typing: a new touch finishes any key still held.
                    pointers.keys.toList().forEach { releasePointer(it, commit = pointers[it]?.gesture?.active != true) }
                }
                pointers[id] = Pointer(key, event.getX(index), event.getY(index), downTime = event.eventTime)
                val text = (key.action as? KeyAction.Text)?.value
                val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                val start = if (!motor.gliding && event.pointerCount == 1 && text?.singleOrNull() in 'a'..'z' && manager?.isTouchExplorationEnabled != true) {
                    listener?.onSwipeStart() ?: SwipeStart.Off
                } else SwipeStart.Off
                if (start != SwipeStart.Off) {
                    letterOrigin()?.let { cell -> pointers[id]?.let { pointer ->
                        val aspect = cell.height() / cell.width()
                        // Unsupported very short/wide cells keep ordinary taps rather than swallowing them.
                        if (aspect in .5f..3f) {
                            pointer.gestureStart = event.eventTime
                            if (start is SwipeStart.Refused) {
                                pointer.refused = SwipeGesture(aspect)
                                pointer.refusal = start.reason
                                sampleGesture(pointer, event, index, pointer.refused)
                            } else {
                                pointer.gesture = SwipeGesture(aspect)
                                sampleGesture(pointer, event, index)
                            }
                        } else if (start == SwipeStart.Accepted) listener?.onSwipeCancel()
                    } }
                }
                // With a hold time or dwell nothing is typed yet: the tap buzz comes when the key is ready or typed.
                if (!motor.timed) KeyHaptics.perform(this, HapticFeedbackConstants.KEYBOARD_TAP)
                onPress(id, key)
                startTiming(id, event.eventTime)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    if (pointerId in ignored) continue
                    val pointer = pointers[pointerId] ?: adopt(pointerId, event, i) ?: continue
                    val paired = pair?.takeIf { it.has(pointerId) }
                    if (paired != null) {
                        if (trackPair(paired, pointerId, event, i)) activatePair(paired)
                        // The held first key stays where it was when the second finger landed.
                        if (paired.active || pointerId == paired.first) continue
                    }
                    if (pointer.consumed) continue
                    pointer.x = event.getX(i)
                    pointer.y = event.getY(i)
                    if (!pointer.longPressed) {
                        sampleGesture(pointer, event, i)
                        if (refusedSwipe(pointer, event, i)) continue
                        if (pointer.gesture?.moved == true) cancelTimers()
                        if (pointer.gesture?.let { it.active || it.cancelled } == true) { cancelTimers(); invalidate(); continue }
                    }
                    pointer.popup?.let { popup ->
                        if (popup.select(event.getX(i), event.getY(i))) KeyHaptics.perform(this, HapticFeedbackConstants.CLOCK_TICK)
                        continue
                    }
                    // A finger in an undecided pair does not slide; it may once the pair is told apart.
                    if (paired == null && slide(pointer)) continue
                    if (inactiveGaps && !pointer.longPressed && keyAt(pointer.x, pointer.y) == null) {
                        if (!pointer.inGap && pair?.first != pointerId) cancelTimers()
                        pointer.timer?.let(handler::removeCallbacks)
                        pointer.timer = null
                        pointer.ready = false
                        pointer.inGap = true
                        invalidate()
                        continue
                    }
                    // While dwelling, delete is a key like the others: the finger glides onto it and rests.
                    if (pointer.longPressed) continue
                    if (pointer.key.action == KeyAction.Backspace && !motor.dwell) {
                        if (pointer.inGap && motor.timed && keyAt(pointer.x, pointer.y) === pointer.key) {
                            pointer.inGap = false
                            startTiming(pointerId, event.eventTime)
                        }
                        continue
                    }
                    val key = aimedKeyAt(event.getX(i), event.getY(i)) ?: continue
                    // Delete acts on press, so a finger sliding onto it keeps the key it started on,
                    // without that key's hold: resting there to erase must not type m's "ɱ".
                    if (key.action == KeyAction.Backspace && !motor.dwell) {
                        cancelTimers()
                        continue
                    }
                    // Steady keys: a shaking finger keeps its key until it is clearly past the edge.
                    if (key !== pointer.key && motor.steadyKeys &&
                        keyBounds(pointer.key)?.let { MotorRules.staysOn(it.box(), event.getX(i), event.getY(i), keyWidth()) } == true) continue
                    if (key !== pointer.key || pointer.inGap) {
                        pointer.inGap = false
                        pointer.key = key
                        cancelTimers()
                        scheduleLongPress(pointerId, key)
                        startTiming(pointerId, event.eventTime)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                lastLift = event.eventTime
                if (ignored.remove(id)) return true
                val paired = pair?.takeIf { it.has(id) }
                if (paired != null) {
                    // The lift's own sample can be the one that shows both fingers on their way.
                    if (trackPair(paired, id, event, index)) activatePair(paired)
                    if (paired.active) {
                        pointers.remove(id)
                        cancelTimers()
                        // The swipe is read once both fingers are up, so a finger that lifts a little later still counts in full.
                        if (pointers.containsKey(if (id == paired.first) paired.second else paired.first)) return true
                        pair = null
                        paired.swipe(event.eventTime)?.let { swipe ->
                            pairsRecognized++
                            KeyHaptics.perform(this, HapticFeedbackConstants.LONG_PRESS)
                            listener?.onTwoFingerSwipe(swipe)
                        }
                        invalidate()
                        return true
                    }
                    pair = null
                    // Not a swipe: the held first key goes in now, before the second, as the fingers landed.
                    releasePointer(paired.first, commit = true)
                    if (id == paired.first) {
                        invalidate()
                        return true
                    }
                }
                pointers[id]?.let {
                    it.x = event.getX(index)
                    it.y = event.getY(index)
                    if (!it.longPressed) {
                        sampleGesture(it, event, index)
                        refusedSwipe(it, event, index)
                    }
                }
                releasePointer(id, commit = true)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> cancelAllPointers()
        }
        return true
    }

    /** One letter key's width in pixels, the unit two-finger swipes and slides are measured in. */
    private fun keyWidth(): Float = (width - 2 * sidePadding) / KeyboardLayout.ROW_UNITS

    /**
     * Whether a second finger on [second] may pair with [first]. Delete acts on press, so it never pairs; nor does a
     * first finger that is holding, sliding, choosing an accent or already swipe typing. The first key must be one whose
     * late commit cannot change the keys under the second finger: a layer key, Enter, an apostrophe or a space on the
     * symbols layers switches layouts, which would drop the second touch.
     */
    private fun pairable(first: Pointer, second: Key): Boolean = !first.longPressed && first.popup == null && !first.sliding &&
        !first.consumed && first.gesture?.let { it.active || it.cancelled } != true &&
        first.downAction != KeyAction.Backspace && second.action != KeyAction.Backspace && when (val action = first.key.action) {
            is KeyAction.Text -> action.value != "'" && action.value != "’"
            KeyAction.Space -> letterOrigin() != null
            KeyAction.Shift -> true
            else -> false
        }

    /** Both fingers of [paired] are on their way together: neither key types now, and no hold fires. */
    private fun activatePair(paired: TwoFingerPair) {
        pairsActive++
        cancelTimers()
        // A swipe-typing start on the first finger ends here, as a second touch ends one.
        if (pointers[paired.first]?.gesture != null) listener?.onSwipeCancel()
        pointers[paired.first]?.consumed = true
        pointers[paired.second]?.consumed = true
        invalidate()
    }

    /** Feeds [paired] every sample of pointer [pointerId] in [event]; true when that made the pair active. */
    private fun trackPair(paired: TwoFingerPair, pointerId: Int, event: MotionEvent, index: Int): Boolean {
        val unit = keyWidth()
        if (unit <= 0f) return false
        var activated = false
        if (event.actionMasked == MotionEvent.ACTION_MOVE) for (h in 0 until event.historySize) {
            activated = paired.add(pointerId, event.getHistoricalX(index, h) / unit, event.getHistoricalY(index, h) / unit,
                event.getHistoricalEventTime(h)) || activated
        }
        return paired.add(pointerId, event.getX(index) / unit, event.getY(index) / unit, event.eventTime) || activated
    }

    /**
     * A press on space that travels along the bar moves the caret; a press on delete that travels left covers
     * whole words. True while the pointer is such a slide, which then never changes key or types on release.
     */
    private fun slide(pointer: Pointer): Boolean {
        // A dwelling finger glides by design; its travel along space or from delete is not a slide.
        if (motor.gliding) return false
        val action = pointer.key.action
        if (action != KeyAction.Space && action != KeyAction.Backspace) return false
        // A finger that came down on comma and dragged onto space is adjusting its key, not sliding.
        if (pointer.downAction != action) return false
        val unit = (width - 2 * sidePadding) / KeyboardLayout.ROW_UNITS
        val dx = pointer.x - pointer.downX
        if (!pointer.sliding) {
            if (pointer.longPressed || listener?.slideAllowed(action) != true) return false
            // A thumb already on its way along the bar is not holding space for the keyboard picker.
            if (action == KeyAction.Space && abs(dx) >= CursorGestures.HOLD_SLOP_WIDTHS * unit) cancelTimers()
            // A slow slide left from delete must not go on erasing letters by repeat on its way to the first word.
            if (action == KeyAction.Backspace && -dx >= CursorGestures.DELETE_SLOP_WIDTHS * unit &&
                abs(dx) >= abs(pointer.y - pointer.downY) * 1.5f) cancelTimers()
            if (!CursorGestures.starts(dx, pointer.y - pointer.downY, unit)) return false
            if (action == KeyAction.Backspace && dx > 0f) return false
            pointer.sliding = true
            // Delete stops repeating: from here the slide decides what goes.
            cancelTimers()
            KeyHaptics.perform(this, HapticFeedbackConstants.LONG_PRESS)
        }
        if (action == KeyAction.Space) {
            val steps = CursorGestures.steps(dx, unit, slideSpeed.caretStep)
            if (steps != pointer.slideSteps) {
                KeyHaptics.perform(this, HapticFeedbackConstants.CLOCK_TICK)
                listener?.onCursorSlide(steps - pointer.slideSteps)
                pointer.slideSteps = steps
            }
        } else {
            val words = (-CursorGestures.steps(dx, unit, slideSpeed.wordStep)).coerceAtLeast(0)
            if (words != pointer.slideSteps || !pointer.slideReported) {
                KeyHaptics.perform(this, HapticFeedbackConstants.CLOCK_TICK)
                listener?.onDeleteSlide(words)
                pointer.slideSteps = words
                pointer.slideReported = true
            }
        }
        return true
    }

    private fun onPress(pointerId: Int, key: Key) {
        cancelTimers()
        if (key.action == KeyAction.Backspace) {
            // With a hold time or dwell, delete fires when its timer runs out instead (startTiming).
            if (motor.timed) return
            // Delete fires on press and repeats while held, accelerating after a moment.
            listener?.onKey(KeyAction.Backspace)
            startDeleteRepeat()
            return
        }
        scheduleLongPress(pointerId, key)
    }

    private fun startDeleteRepeat() {
        repeatRunnable?.let(handler::removeCallbacks)
        var delay = REPEAT_START_MS
        repeatRunnable = object : Runnable {
            override fun run() {
                listener?.onKey(KeyAction.Backspace)
                delay = maxOf(REPEAT_MIN_MS, (delay * 0.85).toLong())
                handler.postDelayed(this, delay)
            }
        }.also { handler.postDelayed(it, TypingRules.holdDelayMs(ViewConfiguration.getLongPressTimeout())) }
    }

    /**
     * Starts the hold time or dwell for the key under [pointerId], measured from [since]. A hold time makes the key
     * ready, after which lifting types it (delete fires then and repeats); a dwell types the key where the finger rests.
     */
    private fun startTiming(pointerId: Int, since: Long) {
        val pointer = pointers[pointerId] ?: return
        pointer.timer?.let(handler::removeCallbacks)
        pointer.timer = null
        pointer.keySince = since
        pointer.ready = false
        pointer.dwelled = false
        val ms = if (motor.dwell) motor.dwellMs else motor.holdMs
        if (ms <= 0) return
        val key = pointer.key
        pointer.timer = Runnable {
            val current = pointers[pointerId]?.takeIf { it.key === key } ?: return@Runnable
            current.timer = null
            if (current.consumed || current.sliding || current.longPressed || current.popup != null ||
                current.gesture?.let { it.active || it.cancelled } == true) return@Runnable
            current.ready = true
            KeyHaptics.perform(this, HapticFeedbackConstants.KEYBOARD_TAP)
            if (motor.dwell) {
                current.dwelled = true
                reportTouch(current)
                listener?.onKey(key.action)
                if (key.action == KeyAction.Backspace) startDeleteRepeat()
            } else if (key.action == KeyAction.Backspace) {
                listener?.onKey(KeyAction.Backspace)
                startDeleteRepeat()
            }
            invalidate()
        }.also { handler.postDelayed(it, (since + ms - SystemClock.uptimeMillis()).coerceAtLeast(0L)) }
    }

    /**
     * While dwelling, a finger still down when the layout changed under it (after ?123 or ABC) carries on with the new
     * keys instead of going dead until it lifts. The key under it counts as typed already, so it must move on first.
     */
    private fun adopt(pointerId: Int, event: MotionEvent, index: Int): Pointer? {
        if (!motor.dwell || pointers.isNotEmpty()) return null
        val key = aimedKeyAt(event.getX(index), event.getY(index)) ?: return null
        return Pointer(key, event.getX(index), event.getY(index), downTime = event.eventTime, dwelled = true).also { pointers[pointerId] = it }
    }

    private fun scheduleLongPress(pointerId: Int, key: Key) {
        val shiftLocks = key.action == KeyAction.Shift && MotorRules.shiftHoldLocks(motor)
        if (key.alternates.isEmpty() && key.action != KeyAction.Space && !shiftLocks) return
        val delay = MotorRules.holdDelay(motor, TypingRules.holdDelayMs(ViewConfiguration.getLongPressTimeout()),
            key.action == KeyAction.Shift) ?: return
        longPressRunnable = Runnable {
            // A second finger held still is a hold, not a swipe: the first finger's key goes in before what the hold types.
            pair?.takeIf { !it.active && it.second == pointerId }?.let { pair = null; releasePointer(it.first, commit = true) }
            val pointer = pointers[pointerId] ?: return@Runnable
            pointer.longPressed = true
            KeyHaptics.perform(this, HapticFeedbackConstants.LONG_PRESS)
            when {
                shiftLocks -> {
                    listener?.onCapsLock()
                    // A dwelling finger glides on from Shift; it already counts as typed there.
                    if (motor.dwell) pointer.longPressed = false
                }
                key.action == KeyAction.Space -> listener?.onLongPressSpace()
                // A single alternate commits right away; several open a popup to slide across.
                key.alternates.size == 1 -> listener?.onKey(KeyAction.Text(casedAlternate(key.alternates[0])))
                else -> keyBounds(key)?.let { cell ->
                    pointer.popup = AlternatesPopup(key.alternates.map(::casedAlternate), cell).also { it.show() }
                }
            }
            invalidate()
        }.also { handler.postDelayed(it, delay) }
    }

    /** Shift capitalizes accented Latin letters only; IPA and Greek symbols keep their exact form. */
    private fun casedAlternate(value: String): String =
        if (shiftState != ShiftState.OFF && value.all { it.code in 0x00C0..0x024F }) value.uppercase() else value

    private fun keyBounds(key: Key): RectF? = layout.rows.withIndex().firstNotNullOfOrNull { (r, row) ->
        row.indexOfFirst { it === key }.takeIf { it >= 0 }?.let { bounds.getOrNull(r)?.getOrNull(it) }
    }

    /**
     * Follows a press whose swipe was refused. Once it travels like a swipe (the [SwipeGesture] that would
     * have decoded it turns active) it types nothing, shows no key pressed, and the listener says why.
     */
    private fun refusedSwipe(pointer: Pointer, event: MotionEvent, index: Int): Boolean {
        val shadow = pointer.refused ?: return false
        sampleGesture(pointer, event, index, shadow)
        if (shadow.cancelled) pointer.refused = null
        if (!shadow.active || shadow.cancelled) return false
        pointer.refused = null
        pointer.consumed = true
        cancelTimers()
        pointer.refusal?.let { listener?.onSwipeRefused(it) }
        invalidate()
        return true
    }

    private fun releasePointer(id: Int, commit: Boolean) {
        val pointer = pointers.remove(id) ?: return
        cancelTimers()
        pointer.timer?.let(handler::removeCallbacks)
        if (pointer.consumed) return
        if (!pointer.longPressed && pointer.gesture?.let { it.active || it.cancelled } == true) {
            val path = pointer.gesture?.finish()
            if (commit && path != null) listener?.onSwipe(path) else listener?.onSwipeCancel()
            return
        }
        pointer.popup?.let { popup ->
            popup.dismiss()
            if (commit) popup.selected()?.let { listener?.onKey(KeyAction.Text(it)) }
            return
        }
        if (pointer.sliding) {
            // The caret is already where the slide left it; a delete slide removes its words only now.
            if (pointer.key.action == KeyAction.Backspace) listener?.onDeleteSlideEnd(commit)
            return
        }
        // UP may enter a gap without an intervening MOVE. Never commit the key retained from DOWN.
        if (inactiveGaps && keyAt(pointer.x, pointer.y) == null) return
        if (commit && !pointer.longPressed && pointer.key.action != KeyAction.Backspace && MotorRules.typesOnLift(motor, pointer.ready)) {
            reportTouch(pointer)
            listener?.onKey(pointer.key.action)
        }
    }

    private fun reportTouch(pointer: Pointer) {
        if (pointer.key.action !is KeyAction.Text) return
        val cell = keyBounds(pointer.key) ?: return
        val keyWidth = cell.width() / pointer.key.width
        if (keyWidth <= 0f) return
        listener?.onKeyTouch(pointer.key, (pointer.x - cell.centerX()) / keyWidth, (pointer.y - cell.centerY()) / cell.height())
    }

    private fun cancelAllPointers() {
        cancelTimers()
        // A key held back for a pair is dropped with the rest, as a cancelled touch types nothing.
        pair = null
        if (pointers.values.any { it.gesture != null }) listener?.onSwipeCancel()
        if (pointers.values.any { it.sliding && it.key.action == KeyAction.Backspace }) listener?.onDeleteSlideEnd(false)
        pointers.values.forEach { pointer ->
            pointer.popup?.dismiss()
            pointer.timer?.let(handler::removeCallbacks)
        }
        pointers.clear()
        invalidate()
    }

    /**
     * Accents and IPA for a held key, drawn in a small window above it. The finger that opened it
     * keeps moving over the keyboard; its position picks the highlighted option.
     */
    private inner class AlternatesPopup(private val options: List<String>, private val anchor: RectF) {
        private val cellWidth = 38f * density
        private val cellHeight = 50f * density
        // The first option sits over the key; the popup mirrors, or narrows and wraps, to stay on screen.
        private val placement = PopupGeometry.place(anchor.centerX(), this@KeyboardView.width.toFloat(), options.size, POPUP_COLUMNS, cellWidth)
        private val columns = placement.columns
        private val rows = (options.size + columns - 1) / columns
        private val popupWidth = (columns * cellWidth).toInt()
        private val popupHeight = (rows * cellHeight).toInt()
        private var selectedIndex = 0
        private val originX = placement.originX
        private val originY = anchor.top - popupHeight - 4f * density

        private val content = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                fill.color = modifierColor
                keyRect.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawRoundRect(keyRect, radius, radius, fill)
                labelPaint.textSize = 22f * scaled
                options.forEachIndexed { index, option ->
                    // The first row sits nearest the finger, so rows grow upward.
                    val column = PopupGeometry.column(index, columns, placement.mirrored)
                    val row = rows - 1 - index / columns
                    val left = column * cellWidth
                    val top = row * cellHeight
                    if (index == selectedIndex) {
                        fill.color = accentColor
                        keyRect.set(left + 3 * density, top + 3 * density, left + cellWidth - 3 * density, top + cellHeight - 3 * density)
                        canvas.drawRoundRect(keyRect, radius, radius, fill)
                    }
                    labelPaint.color = if (index == selectedIndex) onAccentColor else onKeyColor
                    val baseline = top + cellHeight / 2 - (labelPaint.descent() + labelPaint.ascent()) / 2
                    canvas.drawText(option, left + cellWidth / 2, baseline, labelPaint)
                }
            }
        }

        private val window = android.widget.PopupWindow(content, popupWidth, popupHeight, false).apply {
            isClippingEnabled = false
            isTouchable = false
            elevation = 6f * density
        }

        // Android 14 and earlier clamp touches to the keyboard's window (SoftInputWindow.dispatchTouchEvent), so a
        // finger can rise no higher than the window's top, which is above this view by its offset in the window.
        private var reachableTop = Float.NEGATIVE_INFINITY

        fun show() {
            val location = IntArray(2)
            getLocationInWindow(location)
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) reachableTop = -location[1].toFloat()
            window.showAtLocation(this@KeyboardView, android.view.Gravity.NO_GRAVITY, location[0] + originX.toInt(), location[1] + originY.toInt())
            content.contentDescription = options.joinToString(" ")
            announceForAccessibility(options.first())
        }

        /** Picks the option under the finger; returns true when the highlight moved. */
        fun select(x: Float, y: Float): Boolean {
            val index = PopupGeometry.indexAt(x, y, placement, originY, columns, rows, cellWidth, cellHeight, options.size, reachableTop)
            if (index == selectedIndex) return false
            selectedIndex = index
            content.invalidate()
            return true
        }

        fun selected(): String? = options.getOrNull(selectedIndex)

        fun dismiss() = runCatching { window.dismiss() }
    }

    private fun cancelTimers() {
        longPressRunnable?.let(handler::removeCallbacks)
        repeatRunnable?.let(handler::removeCallbacks)
        longPressRunnable = null
        repeatRunnable = null
    }

    override fun onDetachedFromWindow() {
        cancelAllPointers()
        super.onDetachedFromWindow()
    }

    /**
     * Filler resolves to the nearest key. Optional vertical no-touch bands are rejected before learned aim, for
     * touches only ([gaps]): TalkBack's explore by touch reads the nearest key everywhere, bands included.
     */
    private fun keyAt(x: Float, y: Float, gaps: Boolean = inactiveGaps): Key? {
        if (bounds.isEmpty()) return null
        // Rows can differ in height (a shorter number row), so find the row by its bounds.
        val row = bounds.indexOfFirst { cells -> cells.isNotEmpty() && y < cells[0].bottom }.let { if (it < 0) bounds.lastIndex else it }
        if (gaps && bounds[row].firstOrNull()?.let {
                InactiveGaps.accepts(y, it.top, it.bottom, gapY / 2)
            } != true) return null
        return layout.rows[row].withIndex()
            .filter { !it.value.isSpacer }
            .minByOrNull { (i, _) ->
                val cell = bounds[row][i]
                if (x in cell.left..cell.right) 0f else minOf(abs(x - cell.left), abs(x - cell.right))
            }?.value
    }

    /**
     * The key a touch is for: the one drawn under it, or with [MotorSettings.aimFromTouches] the letter whose target,
     * moved by where this person's lifts on it land, the touch falls in. Letters only trade with nearby letters.
     */
    private fun aimedKeyAt(x: Float, y: Float): Key? {
        val raw = keyAt(x, y) ?: return null
        if (!motor.aimFromTouches || !isLetter(raw)) return raw
        val rawCell = keyBounds(raw)?.box() ?: return raw
        val candidates = flatKeys().filter { (key, cell) -> isLetter(key) && MotorRules.nearRows(rawCell, cell.box()) }
        val targets = candidates.map { (key, cell) -> AimTarget(cell.box(), cell.width() / key.width, aimShift(key)) }
        return candidates.getOrNull(MotorRules.aim(x, y, targets, candidates.indexOfFirst { it.first === raw }))?.first ?: raw
    }

    private fun isLetter(key: Key): Boolean = (key.action as? KeyAction.Text)?.value?.singleOrNull()?.isLetter() == true

    private fun RectF.box(): Box = Box(left, top, right, bottom)

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private fun flatKeys(): List<Pair<Key, RectF>> = layout.rows.flatMapIndexed { r, row ->
        row.mapIndexedNotNull { k, key -> if (key.isSpacer) null else bounds.getOrNull(r)?.getOrNull(k)?.let { key to it } }
    }

    /** Exposes each key as a virtual view so TalkBack can explore and type. */
    private inner class KeyAccessibilityHelper : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val key = keyAt(x, y, gaps = false) ?: return INVALID_ID
            return flatKeys().indexOfFirst { it.first === key }.takeIf { it >= 0 } ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            flatKeys().indices.forEach(virtualViewIds::add)
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val (key, rect) = flatKeys().getOrNull(virtualViewId) ?: run {
                node.contentDescription = ""
                node.setBoundsInParent(android.graphics.Rect())
                return
            }
            node.contentDescription = spokenLabel(key)
            node.className = "android.inputmethodservice.Keyboard\$Key"
            node.setBoundsInParent(android.graphics.Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()))
            // Accessibility keeps the full stable cell; direct touch alone has the optional inactive margins.
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.isClickable = true
            // What a hold types (a number's symbol, a letter's first accent), announced as the hold action.
            key.alternates.firstOrNull()?.let { alternate ->
                node.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
                        context.getString(R.string.key_hold_action, KeyboardLayout.spokenName(casedAlternate(alternate))),
                    ),
                )
                node.isLongClickable = true
            }
            // The touch popup needs a sliding finger. Expose every later character as an action too,
            // so choosing symbols first never takes an accent or IPA character away from TalkBack.
            key.alternates.drop(1).forEachIndexed { index, alternate ->
                ALTERNATE_ACTIONS.getOrNull(index)?.let { id ->
                    node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(id,
                        context.getString(R.string.key_hold_action, KeyboardLayout.spokenName(casedAlternate(alternate)))))
                }
            }
            // A slide is not available under touch exploration, so its moves are actions on the same keys.
            if (key.action == KeyAction.Space && listener?.slideAllowed(KeyAction.Space) == true) {
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.key_cursor_left, context.getString(R.string.key_cursor_left)))
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.key_cursor_right, context.getString(R.string.key_cursor_right)))
            }
            if (key.action == KeyAction.Backspace && listener?.slideAllowed(KeyAction.Backspace) == true) {
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.key_delete_word, context.getString(R.string.key_delete_word)))
            }
            if (key.action == KeyAction.Space && listener?.languageChooserOffered() == true) {
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, context.getString(R.string.language_chooser_action)))
                node.isLongClickable = true
            }
            // Two-finger swipes are TalkBack's own under touch exploration, so what they are set to do is here instead.
            if (key.action == KeyAction.Space) listener?.twoFingerActions()?.forEach { (swipe, label) ->
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(gestureActionId(swipe), label))
            }
            // Two shift taps inside the caps-lock window are too fast for TalkBack gestures, so holding Shift locks it.
            if (key.action == KeyAction.Shift) {
                node.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
                        context.getString(if (shiftState == ShiftState.LOCKED) R.string.caps_lock_off_action else R.string.caps_lock_on_action),
                    ),
                )
                node.isLongClickable = true
            }
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val key = flatKeys().getOrNull(virtualViewId)?.first ?: return false
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> listener?.onKey(key.action)
                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> {
                    if (key.action == KeyAction.Shift) {
                        listener?.onCapsLock()
                        // Redrawing the key alone may not be spoken for the focused node.
                        announceForAccessibility(if (shiftState == ShiftState.LOCKED) "Caps lock, on" else "Caps lock, off")
                        return true
                    }
                    if (key.action == KeyAction.Space && listener?.languageChooserOffered() == true) {
                        listener?.onLongPressSpace()
                        return true
                    }
                    val alternate = key.alternates.firstOrNull() ?: return false
                    listener?.onKey(KeyAction.Text(casedAlternate(alternate)))
                }
                R.id.key_cursor_left -> listener?.onCursorSlide(-1)
                R.id.key_cursor_right -> listener?.onCursorSlide(1)
                R.id.key_delete_word -> {
                    listener?.onDeleteSlide(1)
                    listener?.onDeleteSlideEnd(true)
                }
                R.id.key_gesture_left -> listener?.onTwoFingerSwipe(TwoFingerSwipe.LEFT)
                R.id.key_gesture_right -> listener?.onTwoFingerSwipe(TwoFingerSwipe.RIGHT)
                R.id.key_gesture_up -> listener?.onTwoFingerSwipe(TwoFingerSwipe.UP)
                R.id.key_gesture_down -> listener?.onTwoFingerSwipe(TwoFingerSwipe.DOWN)
                else -> {
                    val index = ALTERNATE_ACTIONS.indexOf(action).takeIf { it >= 0 } ?: return false
                    val alternate = key.alternates.getOrNull(index + 1) ?: return false
                    listener?.onKey(KeyAction.Text(casedAlternate(alternate)))
                }
            }
            return true
        }

        private fun gestureActionId(swipe: TwoFingerSwipe): Int = when (swipe) {
            TwoFingerSwipe.LEFT -> R.id.key_gesture_left
            TwoFingerSwipe.RIGHT -> R.id.key_gesture_right
            TwoFingerSwipe.UP -> R.id.key_gesture_up
            TwoFingerSwipe.DOWN -> R.id.key_gesture_down
        }

        private fun spokenLabel(key: Key): String = when {
            key.action == KeyAction.Enter -> enterLabel.takeUnless { it == "↵" } ?: "Return"
            key.action == KeyAction.Shift -> when (shiftState) {
                ShiftState.OFF -> "Shift"
                ShiftState.ON -> "Shift, on"
                ShiftState.LOCKED -> "Caps lock, on"
            }
            key.action is KeyAction.Text && shiftState != ShiftState.OFF && key.label.length == 1 && key.label[0].isLetter() ->
                "Capital ${key.label.uppercase()}"
            else -> key.spokenLabel
        }
    }

    private companion object {
        const val SHIFT = "shift"
        const val SHIFT_ON = "shift_on"
        const val SHIFT_LOCKED = "shift_locked"
        const val BACKSPACE = "backspace"
        const val RETURN = "return"
        const val MIC = "mic"

        // Resource IDs cannot collide with Android's standard accessibility action constants.
        val ALTERNATE_ACTIONS = intArrayOf(
            R.id.key_alternate_1, R.id.key_alternate_2, R.id.key_alternate_3, R.id.key_alternate_4,
            R.id.key_alternate_5, R.id.key_alternate_6, R.id.key_alternate_7, R.id.key_alternate_8,
            R.id.key_alternate_9, R.id.key_alternate_10, R.id.key_alternate_11, R.id.key_alternate_12,
            R.id.key_alternate_13, R.id.key_alternate_14, R.id.key_alternate_15, R.id.key_alternate_16,
        )

        /** Keyed by icon name, or by the Enter label ("Send", "Search", ...) the icon replaces. */
        val icons: Map<String, Path> = mapOf(
            SHIFT to "M12,8.41L16.59,13H14v6h-4v-6H7.41L12,8.41M12,6l-9,9h5v6h8v-6h5L12,6z",
            SHIFT_ON to "M12,4l-9,9h5v6h8v-6h5z",
            SHIFT_LOCKED to "M12,3l-9,9h5v5h8v-5h5zM8,19h8v2h-8z",
            BACKSPACE to "M22,3H7c-0.69,0 -1.23,0.35 -1.59,0.88L0,12l5.41,8.11c0.36,0.53 0.9,0.89 1.59,0.89h15c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM22,19H7.07L2.4,12l4.66,-7H22v14zM10.41,17L14,13.41 17.59,17 19,15.59 15.41,12 19,8.41 17.59,7 14,10.59 10.41,7 9,8.41 12.59,12 9,15.59z",
            RETURN to "M19,7v4H5.83l3.58,-3.59L8,6l-6,6 6,6 1.41,-1.41L5.83,13H21V7z",
            MIC to "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
            "Search" to "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
            "Send" to "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z",
            "Go" to "M12,4l-1.41,1.41L16.17,11H4v2h12.17l-5.58,5.59L12,20l8,-8z",
            "Next" to "M12,4l-1.41,1.41L16.17,11H4v2h12.17l-5.58,5.59L12,20l8,-8z",
            "Done" to "M9,16.2L4.8,12l-1.4,1.4L9,19 21,7l-1.6,-1.4z",
        ).mapValues { PathParser.createPathFromPathData(it.value) }

        const val POPUP_COLUMNS = 8
        const val REPEAT_START_MS = 90L
        const val REPEAT_MIN_MS = 35L
    }
}
