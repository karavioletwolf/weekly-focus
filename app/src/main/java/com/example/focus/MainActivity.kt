package com.example.focus

import android.app.DatePickerDialog
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// 1. Data Models
enum class TaskStatus(val displayName: String) {
    ACTION_NEEDED("Action Needed"),
    IN_PROGRESS("In Progress"),
    UPCOMING("Upcoming"),
    COMPLETED("Completed")
}

data class TaskItem(
    val id: Long = 0,
    val title: String,
    val notes: String = "",
    val status: TaskStatus,
    val previousStatus: TaskStatus = TaskStatus.UPCOMING,
    val epochMillis: Long = System.currentTimeMillis(),
    val dueDateMillis: Long? = null
)

sealed class ListItem {
    data class Header(val title: String, val count: Int) : ListItem()
    data class TaskRow(val task: TaskItem) : ListItem()
}

data class CalendarDay(
    val dayNumber: Int?,
    val dateStr: String?,
    val fullDateMillis: Long?,
    val isToday: Boolean,
    val taskCount: Int
)

// 2. Offline SQLite Helper
class TaskDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "focus.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_TASKS = "tasks"
        const val COL_ID = "_id"
        const val COL_TITLE = "title"
        const val COL_NOTES = "notes"
        const val COL_STATUS = "status"
        const val COL_PREV_STATUS = "previous_status"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_DUE_DATE = "due_date"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createQuery = """
            CREATE TABLE $TABLE_TASKS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TITLE TEXT NOT NULL,
                $COL_NOTES TEXT,
                $COL_STATUS TEXT NOT NULL,
                $COL_PREV_STATUS TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_DUE_DATE INTEGER
            )
        """.trimIndent()
        db.execSQL(createQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_TASKS ADD COLUMN $COL_DUE_DATE INTEGER")
        }
    }

    fun insertTask(task: TaskItem): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TITLE, task.title)
            put(COL_NOTES, task.notes)
            put(COL_STATUS, task.status.name)
            put(COL_PREV_STATUS, task.previousStatus.name)
            put(COL_TIMESTAMP, task.epochMillis)
            if (task.dueDateMillis != null) {
                put(COL_DUE_DATE, task.dueDateMillis)
            } else {
                putNull(COL_DUE_DATE)
            }
        }
        return db.insert(TABLE_TASKS, null, values)
    }

    fun updateTask(task: TaskItem): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TITLE, task.title)
            put(COL_NOTES, task.notes)
            put(COL_STATUS, task.status.name)
            put(COL_PREV_STATUS, task.previousStatus.name)
            if (task.dueDateMillis != null) {
                put(COL_DUE_DATE, task.dueDateMillis)
            } else {
                putNull(COL_DUE_DATE)
            }
        }
        return db.update(TABLE_TASKS, values, "$COL_ID = ?", arrayOf(task.id.toString()))
    }

    fun updateStatus(id: Long, newStatus: TaskStatus, prevStatus: TaskStatus) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_STATUS, newStatus.name)
            put(COL_PREV_STATUS, prevStatus.name)
        }
        db.update(TABLE_TASKS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun getAllTasks(): List<TaskItem> {
        val list = mutableListOf<TaskItem>()
        val db = readableDatabase
        val cursor = db.query(TABLE_TASKS, null, null, null, null, null, "$COL_TIMESTAMP ASC")

        cursor.use {
            if (it.moveToFirst()) {
                val idIdx = it.getColumnIndexOrThrow(COL_ID)
                val titleIdx = it.getColumnIndexOrThrow(COL_TITLE)
                val notesIdx = it.getColumnIndexOrThrow(COL_NOTES)
                val statusIdx = it.getColumnIndexOrThrow(COL_STATUS)
                val prevStatusIdx = it.getColumnIndexOrThrow(COL_PREV_STATUS)
                val timeIdx = it.getColumnIndexOrThrow(COL_TIMESTAMP)
                val dueDateIdx = it.getColumnIndex(COL_DUE_DATE)

                do {
                    val statusStr = it.getString(statusIdx)
                    val prevStatusStr = it.getString(prevStatusIdx)
                    val status = try { TaskStatus.valueOf(statusStr) } catch (e: Exception) { TaskStatus.UPCOMING }
                    val prevStatus = try { TaskStatus.valueOf(prevStatusStr) } catch (e: Exception) { TaskStatus.UPCOMING }

                    val dueDate = if (dueDateIdx != -1 && !it.isNull(dueDateIdx)) {
                        it.getLong(dueDateIdx)
                    } else null

                    list.add(
                        TaskItem(
                            id = it.getLong(idIdx),
                            title = it.getString(titleIdx),
                            notes = it.getString(notesIdx) ?: "",
                            status = status,
                            previousStatus = prevStatus,
                            epochMillis = it.getLong(timeIdx),
                            dueDateMillis = dueDate
                        )
                    )
                } while (it.moveToNext())
            }
        }
        return list
    }

    fun deleteTask(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_TASKS, "$COL_ID = ?", arrayOf(id.toString()))
    }
}

// 3. Custom Month Grid Adapter
class CalendarGridAdapter(
    private var days: List<CalendarDay>,
    private var selectedDateStr: String?,
    private val onDayClick: (CalendarDay) -> Unit
) : RecyclerView.Adapter<CalendarGridAdapter.DayViewHolder>() {

    class DayViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDayNumber: TextView = view.findViewById(R.id.tvDayNumber)
        val tvIndicator: TextView = view.findViewById(R.id.tvDayTaskIndicator)
        val root: View = view.findViewById(R.id.llDayCell)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_day, parent, false)
        return DayViewHolder(view)
    }

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        val day = days[position]

        if (day.dayNumber == null) {
            holder.tvDayNumber.text = ""
            holder.tvIndicator.visibility = View.INVISIBLE
            holder.tvDayNumber.background = null
            holder.root.setOnClickListener(null)
            return
        }

        holder.tvDayNumber.text = "${day.dayNumber}"

        val isSelected = day.dateStr == selectedDateStr
        val context = holder.itemView.context

        if (isSelected) {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(context, R.color.primary))
            }
            holder.tvDayNumber.background = bg
            holder.tvDayNumber.setTextColor(Color.WHITE)
        } else if (day.isToday) {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(3, ContextCompat.getColor(context, R.color.primary))
                setColor(Color.TRANSPARENT)
            }
            holder.tvDayNumber.background = bg
            holder.tvDayNumber.setTextColor(ContextCompat.getColor(context, R.color.primary))
        } else {
            holder.tvDayNumber.background = null
            holder.tvDayNumber.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }

        if (day.taskCount > 0) {
            holder.tvIndicator.visibility = View.VISIBLE
            holder.tvIndicator.text = if (day.taskCount == 1) "●" else "● ${day.taskCount}"
            holder.tvIndicator.setTextColor(
                if (isSelected) ContextCompat.getColor(context, R.color.primary)
                else ContextCompat.getColor(context, R.color.secondary)
            )
        } else {
            holder.tvIndicator.visibility = View.INVISIBLE
        }

        holder.root.setOnClickListener {
            onDayClick(day)
        }
    }

    override fun getItemCount(): Int = days.size

    fun updateData(newDays: List<CalendarDay>, newSelectedDateStr: String?) {
        days = newDays
        selectedDateStr = newSelectedDateStr
        notifyDataSetChanged()
    }
}

// 4. Task List Adapter
class TaskAdapter(
    private var items: MutableList<ListItem>,
    private val onToggleComplete: (TaskItem, Boolean) -> Unit,
    private val onChangeStatus: (TaskItem) -> Unit,
    private val onEdit: (TaskItem) -> Unit,
    private val onDelete: (TaskItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
        private val dateBadgeFormat = SimpleDateFormat("MMM d", Locale.US)
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSectionTitle: TextView = view.findViewById(R.id.tvSectionTitle)
        val tvSectionCount: TextView = view.findViewById(R.id.tvSectionCount)
    }

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbCompleted: CheckBox = view.findViewById(R.id.cbCompleted)
        val tvTaskTitle: TextView = view.findViewById(R.id.tvTaskTitle)
        val tvTaskNotes: TextView = view.findViewById(R.id.tvTaskNotes)
        val tvTaskDueDate: TextView? = view.findViewById(R.id.tvTaskDueDate)
        val tvStatusBadge: TextView = view.findViewById(R.id.tvStatusBadge)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteTask)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ListItem.Header -> TYPE_HEADER
            is ListItem.TaskRow -> TYPE_ROW
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_section_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_task_row, parent, false)
            RowViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> {
                val headerHolder = holder as HeaderViewHolder
                headerHolder.tvSectionTitle.text = item.title
                headerHolder.tvSectionCount.text = "${item.count}"
            }
            is ListItem.TaskRow -> {
                val rowHolder = holder as RowViewHolder
                val task = item.task
                val context = rowHolder.itemView.context
                val isCompleted = task.status == TaskStatus.COMPLETED

                rowHolder.tvTaskTitle.text = "#${task.id}  ${task.title}"

                if (isCompleted) {
                    rowHolder.tvTaskTitle.paintFlags = rowHolder.tvTaskTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    rowHolder.tvTaskTitle.setTextColor(ContextCompat.getColor(context, R.color.text_completed))
                } else {
                    rowHolder.tvTaskTitle.paintFlags = rowHolder.tvTaskTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    rowHolder.tvTaskTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }

                if (task.notes.isNotEmpty()) {
                    rowHolder.tvTaskNotes.visibility = View.VISIBLE
                    rowHolder.tvTaskNotes.text = task.notes
                } else {
                    rowHolder.tvTaskNotes.visibility = View.GONE
                }

                rowHolder.tvTaskDueDate?.let { tvDate ->
                    if (task.dueDateMillis != null) {
                        tvDate.visibility = View.VISIBLE
                        tvDate.text = "📅 " + dateBadgeFormat.format(Date(task.dueDateMillis))
                    } else {
                        tvDate.visibility = View.GONE
                    }
                }

                rowHolder.itemView.setOnClickListener { onEdit(task) }

                rowHolder.cbCompleted.setOnCheckedChangeListener(null)
                rowHolder.cbCompleted.isChecked = isCompleted
                rowHolder.cbCompleted.setOnCheckedChangeListener { _, isChecked ->
                    onToggleComplete(task, isChecked)
                }

                val (bgRes, textRes) = when (task.status) {
                    TaskStatus.ACTION_NEEDED -> Pair(R.color.badge_action_bg, R.color.badge_action_text)
                    TaskStatus.IN_PROGRESS -> Pair(R.color.badge_progress_bg, R.color.badge_progress_text)
                    TaskStatus.UPCOMING -> Pair(R.color.badge_upcoming_bg, R.color.badge_upcoming_text)
                    TaskStatus.COMPLETED -> Pair(R.color.badge_done_bg, R.color.badge_done_text)
                }

                rowHolder.tvStatusBadge.text = task.status.displayName
                rowHolder.tvStatusBadge.setBackgroundColor(ContextCompat.getColor(context, bgRes))
                rowHolder.tvStatusBadge.setTextColor(ContextCompat.getColor(context, textRes))

                rowHolder.tvStatusBadge.setOnClickListener { onChangeStatus(task) }
                rowHolder.btnDelete.setOnClickListener { onDelete(task) }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<ListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}

// 5. Main Activity
class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: TaskDbHelper
    private lateinit var taskAdapter: TaskAdapter
    private lateinit var calendarGridAdapter: CalendarGridAdapter
    private val displayItems = mutableListOf<ListItem>()

    private lateinit var cvDueToday: MaterialCardView
    private lateinit var tvDueTodayTitle: TextView
    private lateinit var tvDueTodayItems: TextView

    private lateinit var tvMonthYear: TextView
    private lateinit var btnPrevMonth: ImageButton
    private lateinit var btnNextMonth: ImageButton
    private lateinit var rvCalendarGrid: RecyclerView

    private lateinit var tvSummarySubtitle: TextView
    private lateinit var tvFilterIndicator: TextView
    private lateinit var btnClearDateFilter: Button
    private lateinit var rvTasks: RecyclerView
    private lateinit var tvEmptyState: TextView

    private val displayedCalendar = Calendar.getInstance()
    private var selectedFilterDateStr: String? = null

    private val dayCompareFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayDateFormat = SimpleDateFormat("EEE, MMM d, yyyy", Locale.US)
    private val monthYearFormat = SimpleDateFormat("MMMM yyyy", Locale.US)

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            writeTasksToCsv(uri)
        }
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importTasksFromCsv(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = TaskDbHelper(this)

        cvDueToday = findViewById(R.id.cvDueToday)
        tvDueTodayTitle = findViewById(R.id.tvDueTodayTitle)
        tvDueTodayItems = findViewById(R.id.tvDueTodayItems)

        tvMonthYear = findViewById(R.id.tvMonthYear)
        btnPrevMonth = findViewById(R.id.btnPrevMonth)
        btnNextMonth = findViewById(R.id.btnNextMonth)
        rvCalendarGrid = findViewById(R.id.rvCalendarGrid)

        tvSummarySubtitle = findViewById(R.id.tvSummarySubtitle)
        tvFilterIndicator = findViewById(R.id.tvFilterIndicator)
        btnClearDateFilter = findViewById(R.id.btnClearDateFilter)
        rvTasks = findViewById(R.id.rvTasks)
        tvEmptyState = findViewById(R.id.tvEmptyState)

        val fabAddTask: FloatingActionButton = findViewById(R.id.fabAddTask)
        val btnExportCsv: ImageButton = findViewById(R.id.btnExportCsv)

        setupRecyclerView()
        setupCalendarGrid()
        loadTasks()

        fabAddTask.setOnClickListener { showAddTaskDialog() }
        btnExportCsv.setOnClickListener { exportToCsv() }
        btnExportCsv.setOnLongClickListener {
            importCsv()
            true
        }

        btnPrevMonth.setOnClickListener {
            displayedCalendar.add(Calendar.MONTH, -1)
            renderCalendar()
        }

        btnNextMonth.setOnClickListener {
            displayedCalendar.add(Calendar.MONTH, 1)
            renderCalendar()
        }

        btnClearDateFilter.setOnClickListener {
            selectedFilterDateStr = null
            tvFilterIndicator.text = "Showing all tasks"
            btnClearDateFilter.visibility = View.GONE
            renderCalendar()
            loadTasks()
        }
    }

    private fun setupRecyclerView() {
        taskAdapter = TaskAdapter(
            displayItems,
            onToggleComplete = { task, isChecked ->
                if (isChecked) {
                    dbHelper.updateStatus(task.id, TaskStatus.COMPLETED, task.status)
                } else {
                    val restoreStatus = if (task.previousStatus != TaskStatus.COMPLETED) task.previousStatus else TaskStatus.UPCOMING
                    dbHelper.updateStatus(task.id, restoreStatus, TaskStatus.UPCOMING)
                }
                loadTasks()
            },
            onChangeStatus = { task -> showChangeStatusDialog(task) },
            onEdit = { task -> showEditTaskDialog(task) },
            onDelete = { task -> confirmDelete(task) }
        )
        rvTasks.layoutManager = LinearLayoutManager(this)
        rvTasks.adapter = taskAdapter
    }

    private fun setupCalendarGrid() {
        rvCalendarGrid.layoutManager = GridLayoutManager(this, 7)
        calendarGridAdapter = CalendarGridAdapter(emptyList(), selectedFilterDateStr) { clickedDay ->
            if (clickedDay.dateStr != null && clickedDay.fullDateMillis != null) {
                selectedFilterDateStr = clickedDay.dateStr
                val readableDate = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(clickedDay.fullDateMillis))
                tvFilterIndicator.text = "Filtered by date: $readableDate"
                btnClearDateFilter.visibility = View.VISIBLE

                renderCalendar()
                loadTasks()

                val allTasks = dbHelper.getAllTasks()
                val tasksForDay = allTasks.filter {
                    it.dueDateMillis != null && dayCompareFormat.format(Date(it.dueDateMillis)) == clickedDay.dateStr
                }
                showTasksDuePopup(readableDate, tasksForDay, clickedDay.fullDateMillis)
            }
        }
        rvCalendarGrid.adapter = calendarGridAdapter
    }

    private fun renderCalendar() {
        tvMonthYear.text = monthYearFormat.format(displayedCalendar.time)

        val cal = displayedCalendar.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)

        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1 // Sunday = 0
        val maxDaysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val allTasks = dbHelper.getAllTasks()
        val taskCountMap = mutableMapOf<String, Int>()
        for (task in allTasks) {
            if (task.dueDateMillis != null) {
                val dStr = dayCompareFormat.format(Date(task.dueDateMillis))
                taskCountMap[dStr] = (taskCountMap[dStr] ?: 0) + 1
            }
        }

        val todayStr = dayCompareFormat.format(Date())
        val daysList = mutableListOf<CalendarDay>()

        for (i in 0 until firstDayOfWeek) {
            daysList.add(CalendarDay(null, null, null, false, 0))
        }

        for (day in 1..maxDaysInMonth) {
            cal.set(Calendar.DAY_OF_MONTH, day)
            val dStr = dayCompareFormat.format(cal.time)
            val isToday = dStr == todayStr
            val count = taskCountMap[dStr] ?: 0
            daysList.add(CalendarDay(day, dStr, cal.timeInMillis, isToday, count))
        }

        calendarGridAdapter.updateData(daysList, selectedFilterDateStr)
    }

    private fun loadTasks() {
        val allTasks = dbHelper.getAllTasks()
        val todayStr = dayCompareFormat.format(Date())

        // 1. Update "Due Today" banner
        val dueTodayTasks = allTasks.filter { task ->
            task.dueDateMillis != null &&
                    dayCompareFormat.format(Date(task.dueDateMillis)) == todayStr &&
                    task.status != TaskStatus.COMPLETED
        }

        cvDueToday.visibility = View.VISIBLE
        if (dueTodayTasks.isNotEmpty()) {
            tvDueTodayTitle.text = "⚡ DUE TODAY (${dueTodayTasks.size})"
            tvDueTodayTitle.setTextColor(Color.parseColor("#E65100"))
            tvDueTodayItems.text = dueTodayTasks.joinToString("\n") { task ->
                "#${task.id} • ${task.title}  [${task.status.displayName}]"
            }
            cvDueToday.setOnClickListener {
                showTasksDuePopup("Today", dueTodayTasks, System.currentTimeMillis())
            }
        } else {
            val todayReadable = SimpleDateFormat("MMM d", Locale.US).format(Date())
            tvDueTodayTitle.text = "⚡ TODAY ($todayReadable)"
            tvDueTodayTitle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tvDueTodayItems.text = "No deadlines scheduled for today. All clear!"
            cvDueToday.setOnClickListener(null)
        }

        // 2. Render Calendar Grid
        renderCalendar()

        // 3. Filter tasks for list
        val filteredTasks = if (selectedFilterDateStr != null) {
            allTasks.filter { task ->
                task.dueDateMillis != null && dayCompareFormat.format(Date(task.dueDateMillis)) == selectedFilterDateStr
            }
        } else {
            allTasks
        }

        if (filteredTasks.isEmpty()) {
            tvEmptyState.visibility = View.VISIBLE
            rvTasks.visibility = View.GONE
            tvEmptyState.text = if (selectedFilterDateStr != null) {
                "No tasks scheduled for this day."
            } else {
                "No focus reminders yet. Tap + to add one."
            }
            tvSummarySubtitle.text = "0 action needed • 0 in progress • 0 done"
            return
        }

        tvEmptyState.visibility = View.GONE
        rvTasks.visibility = View.VISIBLE

        // Sort items by due date ascending (earliest / closest date first; items without due date appear last)
        val dateComparator = compareBy<TaskItem> { it.dueDateMillis ?: Long.MAX_VALUE }
            .thenBy { it.epochMillis }

        val actionNeeded = filteredTasks
            .filter { it.status == TaskStatus.ACTION_NEEDED }
            .sortedWith(dateComparator)

        val inProgress = filteredTasks
            .filter { it.status == TaskStatus.IN_PROGRESS }
            .sortedWith(dateComparator)

        val upcoming = filteredTasks
            .filter { it.status == TaskStatus.UPCOMING }
            .sortedWith(dateComparator)

        val completed = filteredTasks
            .filter { it.status == TaskStatus.COMPLETED }
            .sortedWith(dateComparator)

        tvSummarySubtitle.text = "${actionNeeded.size} action needed • ${inProgress.size} in progress • ${completed.size} done"

        val structuredList = mutableListOf<ListItem>()

        if (actionNeeded.isNotEmpty()) {
            structuredList.add(ListItem.Header("ACTION NEEDED", actionNeeded.size))
            structuredList.addAll(actionNeeded.map { ListItem.TaskRow(it) })
        }

        if (inProgress.isNotEmpty()) {
            structuredList.add(ListItem.Header("IN PROGRESS", inProgress.size))
            structuredList.addAll(inProgress.map { ListItem.TaskRow(it) })
        }

        if (upcoming.isNotEmpty()) {
            structuredList.add(ListItem.Header("UPCOMING", upcoming.size))
            structuredList.addAll(upcoming.map { ListItem.TaskRow(it) })
        }

        if (completed.isNotEmpty()) {
            structuredList.add(ListItem.Header("COMPLETED (${completed.size} ACCOMPLISHED)", completed.size))
            structuredList.addAll(completed.map { ListItem.TaskRow(it) })
        }

        taskAdapter.updateItems(structuredList)
    }

    private fun showTasksDuePopup(readableDate: String, tasks: List<TaskItem>, dateMillis: Long) {
        val builder = AlertDialog.Builder(this)

        if (tasks.isEmpty()) {
            builder.setTitle("Tasks for $readableDate")
            builder.setMessage("No tasks scheduled for this day.")
            builder.setPositiveButton("Add Task for this Day") { _, _ ->
                showAddTaskDialog(defaultDateMillis = dateMillis)
            }
            builder.setNegativeButton("Close", null)
        } else {
            builder.setTitle("$readableDate (${tasks.size} Due)")
            val itemSummaries = tasks.map { task ->
                "#${task.id}  ${task.title}  [${task.status.displayName}]"
            }.toTypedArray()

            builder.setItems(itemSummaries) { _, index ->
                showEditTaskDialog(tasks[index])
            }
            builder.setPositiveButton("Add Another") { _, _ ->
                showAddTaskDialog(defaultDateMillis = dateMillis)
            }
            builder.setNegativeButton("Close", null)
        }

        builder.show()
    }

    private fun showAddTaskDialog(defaultDateMillis: Long? = null) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_task, null)
        val spStatus: Spinner = dialogView.findViewById(R.id.spInitialStatus)
        val etTitle: EditText = dialogView.findViewById(R.id.etTaskTitle)
        val etNotes: EditText = dialogView.findViewById(R.id.etTaskNotes)
        val btnPickDate: Button = dialogView.findViewById(R.id.btnPickDate)
        val btnCancel: Button = dialogView.findViewById(R.id.btnCancelTask)
        val btnSave: Button = dialogView.findViewById(R.id.btnSaveTask)

        var chosenDueDateMillis: Long? = defaultDateMillis
        val calendar = Calendar.getInstance()

        if (chosenDueDateMillis != null) {
            calendar.timeInMillis = chosenDueDateMillis!!
            btnPickDate.text = "Due: " + displayDateFormat.format(Date(chosenDueDateMillis!!))
        }

        btnPickDate.setOnClickListener {
            val datePicker = DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    calendar.set(year, month, dayOfMonth, 12, 0, 0)
                    chosenDueDateMillis = calendar.timeInMillis
                    btnPickDate.text = "Due: " + displayDateFormat.format(calendar.time)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )

            datePicker.setButton(DialogInterface.BUTTON_NEUTRAL, "Remove Date") { _, _ ->
                chosenDueDateMillis = null
                btnPickDate.text = "Set Due Date (Optional)"
            }

            datePicker.show()
        }

        val activeStatuses = arrayOf(
            TaskStatus.UPCOMING.displayName,
            TaskStatus.IN_PROGRESS.displayName,
            TaskStatus.ACTION_NEEDED.displayName
        )
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, activeStatuses)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spStatus.adapter = spinnerAdapter

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val title = etTitle.text.toString().trim()
            if (title.isEmpty()) {
                Toast.makeText(this, "Please enter a task description.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val chosenStatus = when (spStatus.selectedItemPosition) {
                0 -> TaskStatus.UPCOMING
                1 -> TaskStatus.IN_PROGRESS
                else -> TaskStatus.ACTION_NEEDED
            }

            val newTask = TaskItem(
                title = title,
                notes = etNotes.text.toString().trim(),
                status = chosenStatus,
                previousStatus = chosenStatus,
                epochMillis = System.currentTimeMillis(),
                dueDateMillis = chosenDueDateMillis
            )

            dbHelper.insertTask(newTask)
            loadTasks()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showEditTaskDialog(task: TaskItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_task, null)
        val tvTitle: TextView? = dialogView.findViewById(R.id.tvDialogTitle)
        val spStatus: Spinner = dialogView.findViewById(R.id.spInitialStatus)
        val etTitle: EditText = dialogView.findViewById(R.id.etTaskTitle)
        val etNotes: EditText = dialogView.findViewById(R.id.etTaskNotes)
        val btnPickDate: Button = dialogView.findViewById(R.id.btnPickDate)
        val btnCancel: Button = dialogView.findViewById(R.id.btnCancelTask)
        val btnSave: Button = dialogView.findViewById(R.id.btnSaveTask)

        tvTitle?.text = "Edit Reminder #${task.id}"
        btnSave.text = "Save Changes"

        etTitle.setText(task.title)
        etNotes.setText(task.notes)

        var chosenDueDateMillis: Long? = task.dueDateMillis
        val calendar = Calendar.getInstance()
        if (chosenDueDateMillis != null) {
            calendar.timeInMillis = chosenDueDateMillis!!
            btnPickDate.text = "Due: " + displayDateFormat.format(Date(chosenDueDateMillis!!))
        } else {
            btnPickDate.text = "Set Due Date (Optional)"
        }

        btnPickDate.setOnClickListener {
            val datePicker = DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    calendar.set(year, month, dayOfMonth, 12, 0, 0)
                    chosenDueDateMillis = calendar.timeInMillis
                    btnPickDate.text = "Due: " + displayDateFormat.format(calendar.time)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )

            datePicker.setButton(DialogInterface.BUTTON_NEUTRAL, "Remove Date") { _, _ ->
                chosenDueDateMillis = null
                btnPickDate.text = "Set Due Date (Optional)"
            }

            datePicker.show()
        }

        val allStatuses = arrayOf(
            TaskStatus.ACTION_NEEDED.displayName,
            TaskStatus.IN_PROGRESS.displayName,
            TaskStatus.UPCOMING.displayName,
            TaskStatus.COMPLETED.displayName
        )
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, allStatuses)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spStatus.adapter = spinnerAdapter

        val statusPosition = when (task.status) {
            TaskStatus.ACTION_NEEDED -> 0
            TaskStatus.IN_PROGRESS -> 1
            TaskStatus.UPCOMING -> 2
            TaskStatus.COMPLETED -> 3
        }
        spStatus.setSelection(statusPosition)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val title = etTitle.text.toString().trim()
            if (title.isEmpty()) {
                Toast.makeText(this, "Please enter a task description.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val updatedStatus = when (spStatus.selectedItemPosition) {
                0 -> TaskStatus.ACTION_NEEDED
                1 -> TaskStatus.IN_PROGRESS
                2 -> TaskStatus.UPCOMING
                else -> TaskStatus.COMPLETED
            }

            val updatedTask = task.copy(
                title = title,
                notes = etNotes.text.toString().trim(),
                status = updatedStatus,
                dueDateMillis = chosenDueDateMillis
            )

            dbHelper.updateTask(updatedTask)
            loadTasks()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showChangeStatusDialog(task: TaskItem) {
        val statuses = TaskStatus.values()
        val names = statuses.map { it.displayName }.toTypedArray()
        val currentIndex = statuses.indexOf(task.status)

        AlertDialog.Builder(this)
            .setTitle("Move Task #${task.id} To")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                val selectedStatus = statuses[which]
                dbHelper.updateStatus(task.id, selectedStatus, task.status)
                loadTasks()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(task: TaskItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Task #${task.id}?")
            .setMessage("Do you want to delete '${task.title}'?")
            .setPositiveButton("Delete") { _, _ ->
                dbHelper.deleteTask(task.id)
                loadTasks()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportToCsv() {
        val tasks = dbHelper.getAllTasks()
        if (tasks.isEmpty()) {
            Toast.makeText(this, "No reminders to export yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        createDocumentLauncher.launch("Focus_${timeStamp}.csv")
    }

    private fun importCsv() {
        openDocumentLauncher.launch(arrayOf("text/*", "text/comma-separated-values", "*/*"))
    }

    private fun importTasksFromCsv(uri: Uri) {
        val createdFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val dueFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var importedCount = 0

        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine() // Header line
                    
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line?.trim() ?: continue
                        if (currentLine.isEmpty()) continue

                        val tokens = currentLine.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
                            .map { it.trim().removeSurrounding("\"").replace("\"\"", "\"") }

                        if (tokens.size >= 6) {
                            val title = tokens[1]
                            val statusDisplayName = tokens[2]
                            val dueDateStr = tokens[3]
                            val notes = tokens[4]
                            val createdDateStr = tokens[5]

                            val status = TaskStatus.values().find { 
                                it.displayName.equals(statusDisplayName, ignoreCase = true) 
                            } ?: TaskStatus.UPCOMING

                            val dueDateMillis = if (dueDateStr.isNotEmpty()) {
                                try { dueFormat.parse(dueDateStr)?.time } catch (e: Exception) { null }
                            } else null

                            val epochMillis = if (createdDateStr.isNotEmpty()) {
                                try { createdFormat.parse(createdDateStr)?.time ?: System.currentTimeMillis() } 
                                catch (e: Exception) { System.currentTimeMillis() }
                            } else System.currentTimeMillis()

                            val task = TaskItem(
                                title = title,
                                notes = notes,
                                status = status,
                                previousStatus = if (status == TaskStatus.COMPLETED) TaskStatus.UPCOMING else status,
                                epochMillis = epochMillis,
                                dueDateMillis = dueDateMillis
                            )

                            dbHelper.insertTask(task)
                            importedCount++
                        }
                    }
                }
            }

            loadTasks()
            Toast.makeText(this, "Successfully imported $importedCount items.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun writeTasksToCsv(uri: Uri) {
        val tasks = dbHelper.getAllTasks()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val dueFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { writer ->
                    writer.write("ID,Title,Status,Due Date,Notes,Date Created\n")
                    for (t in tasks) {
                        val safeTitle = t.title.replace("\"", "\"\"")
                        val safeNotes = t.notes.replace("\"", "\"\"")
                        val dateCreated = dateFormat.format(Date(t.epochMillis))
                        val dueStr = if (t.dueDateMillis != null) dueFormat.format(Date(t.dueDateMillis)) else ""
                        writer.write("\"#${t.id}\",\"$safeTitle\",\"${t.status.displayName}\",\"$dueStr\",\"$safeNotes\",\"$dateCreated\"\n")
                    }
                }
            }
            Toast.makeText(this, "Focus tasks exported successfully.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
