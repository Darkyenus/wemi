package com.darkyen.wemi.intellij.ui

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.openapi.externalSystem.util.PaintAwarePanel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.AbstractTableCellEditor
import com.intellij.util.ui.GridBag
import com.intellij.util.ui.JBEmptyBorder
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.util.regex.Pattern
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer
import kotlin.reflect.KMutableProperty0

interface PropertyEditor<T> {
	val component: JComponent

	fun setComponentFromProperty()

	fun setPropertyFromComponent()
}

/**
 * Abstract binding of a [property] onto [JComponent]
 */
abstract class AbstractPropertyEditor<T>(private val property: KMutableProperty0<T>) : PropertyEditor<T> {

	abstract override val component: JComponent

	abstract var componentValue:T

	override fun setComponentFromProperty() {
		componentValue = property.get()
	}

	override fun setPropertyFromComponent() {
		property.set(componentValue)
	}
}

class PropertyEditorPanel : PaintAwarePanel(GridBagLayout()) {

	private val gridBag = GridBag().apply {
		defaultInsets = JBInsets.create(5, 5)
		defaultAnchor = GridBagConstraints.LINE_START
		defaultFill = GridBagConstraints.HORIZONTAL
		defaultWeightX = 1.0
	}
	private val editors = ArrayList<PropertyEditor<*>>()

	fun edit(editor:PropertyEditor<*>) {
		add(editor.component, gridBag.nextLine().next())
		editors.add(editor)
	}

	fun gap(height:Int = 200) {
		add(Box.Filler(Dimension(0, 0), Dimension(0, height), null), gridBag.nextLine().next().coverLine().fillCellHorizontally())
	}

	fun loadFromProperties() {
		for (editor in editors) {
			editor.setComponentFromProperty()
		}
	}

	fun saveToProperties() {
		for (editor in editors) {
			editor.setPropertyFromComponent()
		}
	}
}

class TaskListPropertyEditor(property: KMutableProperty0<List<Array<String>>>) : AbstractPropertyEditor<List<Array<String>>>(property) {

	private val tableModel = object : AbstractTableModel() {

		private val mutableTasks = ArrayList<Array<String>>()

		var tasks:List<Array<String>>
			get() = mutableTasks
			set(value) {
				mutableTasks.clear()
				mutableTasks.addAll(value)
				fireTableDataChanged()
			}

		fun addNewTask(task:Array<String>) {
			mutableTasks.add(task)
			fireTableRowsInserted(mutableTasks.size - 1, mutableTasks.size)
		}

		fun removeTasks(row:Int) {
			if (row in mutableTasks.indices) {
				mutableTasks.removeAt(row)
				fireTableRowsDeleted(row, row + 1)
			}
		}

		override fun getRowCount(): Int = mutableTasks.size

		override fun getColumnCount(): Int = 1

		override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
			if (columnIndex != 0 || rowIndex < 0 || rowIndex >= mutableTasks.size) {
				return null
			}
			return mutableTasks[rowIndex]
		}

		override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
			if (columnIndex != 0 || rowIndex < 0 || rowIndex >= mutableTasks.size || aValue !is Array<*>) {
				return
			}
			@Suppress("UNCHECKED_CAST")
			mutableTasks[rowIndex] = aValue as Array<String>
			fireTableCellUpdated(rowIndex, columnIndex)
		}

		override fun getColumnClass(columnIndex: Int): Class<*> = Array<String>::class.java

		override fun getColumnName(column: Int): String = "Task"

		override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
			return !(columnIndex != 0 || rowIndex < 0 || rowIndex >= mutableTasks.size)
		}
	}

	private val table = JBTable(tableModel).apply {
		emptyText.text = "No tasks"
		setShowColumns(false)
		autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
		setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
		rowSelectionAllowed = false
		columnSelectionAllowed = false

		setDefaultRenderer(Array<String>::class.java, object : TableCellRenderer {

			val label = JBLabel(UIUtil.ComponentStyle.REGULAR).apply {
				border = JBEmptyBorder(4)
			}

			override fun getTableCellRendererComponent(table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
				label.text = (value as? Array<*>?)?.joinToString(" ") ?: ""
				label.foreground = JBUI.CurrentTheme.Label.foreground(isSelected)
				return label
			}
		})
		setDefaultEditor(Array<String>::class.java, object : AbstractTableCellEditor() {

			// TODO(jp): Sometimes, when clicking, it does not exactly work, not sure why. Sometimes just caret isn't visible and typing is not possible, sometimes it doesn't focus at all.

			private val textField = JBTextField().apply {
				border = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), 2), JBEmptyBorder(4))
				addActionListener {
					stopCellEditing()
				}
			}
			private val splitPattern = Pattern.compile("\\s+")

			override fun getTableCellEditorComponent(table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
				textField.text = (value as? Array<*>?)?.joinToString(" ") ?: ""
				return textField
			}

			override fun getCellEditorValue(): Array<String> {
				val text = textField.text?.trim() ?: ""
				return if (text.isBlank()) {
					emptyArray()
				} else {
					text.split(splitPattern).toTypedArray()
				}
			}
		})
	}

	private val tableDecorator = ToolbarDecorator.createDecorator(table).run {
		setAddActionName("Add task")
		setAddAction {
			tableModel.addNewTask(emptyArray())
			table.editCellAt(tableModel.rowCount - 1, 0)
		}

		setRemoveActionName("Remove task")
		setRemoveAction {
			val row = table.editingRow
			table.cellEditor.stopCellEditing()
			tableModel.removeTasks(row)
		}
		setRemoveActionUpdater {
			table.isEditing
		}

		createPanel()
	}

	override val component: JComponent = JPanel(BorderLayout(0, 0)).apply {
		add(JBScrollPane(table, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED).apply {
			verticalScrollBar.unitIncrement = 1
		}, BorderLayout.CENTER)
		add(tableDecorator, BorderLayout.PAGE_END)
		preferredSize = Dimension(100, 150)
	}

	override var componentValue: List<Array<String>>
		get() = tableModel.tasks.filter { it.isNotEmpty() }
		set(value) {
			tableModel.tasks = value
		}
}

class EnvironmentVariablesEditor(
		private val envVarsProperty:KMutableProperty0<Map<String, String>>,
		private val passParentEnvProperty:KMutableProperty0<Boolean>) : PropertyEditor<EnvironmentVariablesData> {

	override val component: EnvironmentVariablesComponent = EnvironmentVariablesComponent()

	override fun setComponentFromProperty() {
		component.envs = envVarsProperty.get()
		component.isPassParentEnvs = passParentEnvProperty.get()
	}

	override fun setPropertyFromComponent() {
		envVarsProperty.set(component.envs)
		passParentEnvProperty.set(component.isPassParentEnvs)
	}
}

class BooleanPropertyEditor(property:KMutableProperty0<Boolean>,
                            private val title:String,
                            private val trueInfo:String,
                            private val falseInfo:String) : AbstractPropertyEditor<Boolean>(property) {

	private val checkBox = JBCheckBox(checkBoxText(false), false).apply {
		horizontalAlignment = SwingConstants.LEADING
		addChangeListener {
			this@apply.text = checkBoxText(isSelected)
		}
	}

	private fun checkBoxText(selected:Boolean):String {
		return "$title (${if (selected) trueInfo else falseInfo})"
	}

	override val component: JComponent = checkBox

	override var componentValue: Boolean
		get() = checkBox.isSelected
		set(value) {
			checkBox.isSelected = value
		}
}
