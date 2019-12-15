package com.darkyen.wemi.intellij.ui

import com.darkyen.wemi.intellij.util.MAX_JAVA_VERSION_FOR_WEMI_HINT
import com.darkyen.wemi.intellij.util.MIN_JAVA_VERSION_FOR_WEMI
import com.darkyen.wemi.intellij.util.SDK_TYPE_FOR_WEMI
import com.darkyen.wemi.intellij.util.getWemiCompatibleSdkList
import com.darkyen.wemi.intellij.util.shellCommandExecutable
import com.esotericsoftware.tablelayout.BaseTableLayout
import com.esotericsoftware.tablelayout.Cell
import com.esotericsoftware.tablelayout.swing.Table
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.progress.PerformInBackgroundOption
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ui.configuration.JdkComboBox
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.UserActivityProviderComponent
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.AbstractTableCellEditor
import com.intellij.util.ui.JBEmptyBorder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
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

class PropertyEditorPanel : Table() {

	private val editors = ArrayList<PropertyEditor<*>>()

	init {
		pad(5f)
		defaults().expandX().fillX().spaceBottom(10f)
		align(BaseTableLayout.TOP)
	}

	fun editRow(editor:PropertyEditor<*>) {
		editors.add(editor)
		addCell(editor.component).colspan(2).row()
	}

	fun edit(editor:PropertyEditor<*>): Cell<JComponent> {
		editors.add(editor)
		@Suppress("UNCHECKED_CAST")
		return addCell(editor.component) as Cell<JComponent>
	}

	fun gap(height:Int = 200) {
		addCell().height(height.toFloat()).row()
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
                            private val trueInfo:String? = null,
                            private val falseInfo:String? = null) : AbstractPropertyEditor<Boolean>(property) {

	private val checkBox = JBCheckBox(checkBoxText(false), false).apply {
		horizontalAlignment = SwingConstants.LEADING
		addChangeListener {
			this@apply.text = checkBoxText(isSelected)
		}
	}

	private fun checkBoxText(selected:Boolean):String {
		return if (selected && trueInfo != null) {
			"$title ($trueInfo)"
		} else if (!selected && falseInfo != null) {
			"$title ($falseInfo)"
		} else {
			title
		}
	}

	override val component: JComponent = checkBox

	override var componentValue: Boolean
		get() = checkBox.isSelected
		set(value) {
			checkBox.isSelected = value
		}
}

class WemiJavaExecutableEditor(property:KMutableProperty0<String>) : AbstractPropertyEditor<String>(property) {

	private val possibleJavaSdkList = getWemiCompatibleSdkList()

	private val panel0JavaFromPath = JBLabel("").apply { verticalAlignment = SwingConstants.CENTER }

	init {
		ProgressManager.getInstance().run(object : Task.Backgroundable(null, "Check which Java is on PATH", false, PerformInBackgroundOption.ALWAYS_BACKGROUND) {
			override fun run(indicator: ProgressIndicator) {
				val javaPath = shellCommandExecutable("java")
				ApplicationManager.getApplication().invokeLater({
					if (javaPath == null) {
						panel0JavaFromPath.text = " (currently none)"
					} else {
						panel0JavaFromPath.text = " (currently $javaPath)"
					}
				}, ModalityState.any())
			}
		})
	}

	private val panel1JavaFromPath = JBIntSpinner(MAX_JAVA_VERSION_FOR_WEMI_HINT, MIN_JAVA_VERSION_FOR_WEMI, Integer.MAX_VALUE, 1)
	// NOTE: Other editors use JrePathEditor here, but I don't understand its API
	private val panel2JavaFromSdk = JdkComboBox(ProjectSdksModel().apply {
		for (sdk in possibleJavaSdkList) {
			addSdk(sdk)
		}
	})
	private val panel3JavaCustom = run {
		val textField = ExtendableTextField()

		// TODO(jp): Use BrowseFolderRunnable if it ever stabilizes
		val action = Runnable {
			val fileChooserDescriptor = FileChooserDescriptor(false, true, false, false, false, false)
			fileChooserDescriptor.title = "Select JRE"
			fileChooserDescriptor.description = "Select directory with JRE to run Wemi with"

			FileChooser.chooseFile(fileChooserDescriptor, null, textField, findInitialFile(textField.text)) { chosenFile: VirtualFile? ->
				textField.text = chosenFile?.presentableUrl ?: ""
			}
		}

		val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)
		val browseExtension = ExtendableTextComponent.Extension.create(AllIcons.General.OpenDisk, AllIcons.General.OpenDiskHover, "Browse (" + KeymapUtil.getKeystrokeText(keyStroke) + ")", action)

		object : DumbAwareAction() {
			override fun actionPerformed(e: AnActionEvent) {
				action.run()
			}
		}.registerCustomShortcutSet(CustomShortcutSet(keyStroke), textField)
		textField.addExtension(browseExtension)

		textField
	}

	private fun panelWrap(label:String, c:JComponent, center:Boolean = false):JComponent {
		return JPanel().apply {
			layout = BoxLayout(this, BoxLayout.LINE_AXIS)
			if (center) {
				add(Box.createHorizontalGlue())
			}

			add(JBLabel(label).apply {
				verticalAlignment = SwingConstants.CENTER
				alignmentY = Component.CENTER_ALIGNMENT
			})
			c.alignmentY = Component.CENTER_ALIGNMENT
			add(c)

			if (center) {
				add(Box.createHorizontalGlue())
			}

			background = JBUI.CurrentTheme.CustomFrameDecorations.titlePaneBackground()
			border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
		}
	}

	private val panel = object : JBTabbedPane(TOP), UserActivityProviderComponent {
		init {
			addTab("From PATH", panelWrap("Using Java on PATH", panel0JavaFromPath, center = true))
			addTab("Specific version", panelWrap("Java version: ", panel1JavaFromPath, center = true))
			addTab("SDK", panelWrap("JRE/JDK:", panel2JavaFromSdk))
			addTab("Custom", panelWrap("JRE/JDK/Java executable:", panel3JavaCustom))

			border = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1, true), "Java Executable")
		}
	}

	override val component: JComponent = panel

	override var componentValue: String
		get() = when (panel.selectedIndex) {
			0 -> "" // From PATH
			1 -> panel1JavaFromPath.number.toString()
			2 -> panel2JavaFromSdk.selectedJdk?.let { sdk ->
				javaFromSdk(sdk)?.toString()
			}
			3 -> findExistingFileFromAlternatives(Paths.get(panel3JavaCustom.text), "java", "java.exe", "bin/java", "bin/java.exe")?.toString()
			else -> null
		} ?: ""
		set(value) {
			if (value.isBlank()) {
				panel.selectedIndex = 0
				return
			}
			val version = value.toIntOrNull()
			if (version != null) {
				panel.selectedIndex = 1
				panel1JavaFromPath.number = version
				return
			}
			var path = Paths.get(value)
			if (path.endsWith("bin/java") || path.endsWith("bin/java.exe")) {
				path = path.parent.parent
			}

			val realPath = try { path.toRealPath() } catch(e: IOException) { path.toAbsolutePath() }
			val sdk = possibleJavaSdkList.find {
				val sdkPath = Paths.get(it.homePath ?: return@find false)
				val sdkRealPath = try { sdkPath.toRealPath() } catch(e: IOException) { sdkPath.toAbsolutePath() }
				realPath.startsWith(sdkRealPath)
			}

			if (sdk != null) {
				panel.selectedIndex = 2
				panel2JavaFromSdk.selectedJdk = sdk
				panel3JavaCustom.text = javaFromSdk(sdk)?.toString() ?: ""
				return
			}

			panel.selectedIndex = 3
			panel3JavaCustom.text = value
		}

	private fun findInitialFile(path:String?):VirtualFile? {
		if (path == null || path.isBlank()) {
			return null
		}

		var directoryName = FileUtil.toSystemIndependentName(path.trim())
		var resultPath = LocalFileSystem.getInstance().findFileByPath(directoryName)
		while (resultPath == null && directoryName.isNotEmpty()) {
			val pos = directoryName.lastIndexOf('/')
			if (pos <= 0) break
			directoryName = directoryName.substring(0, pos)
			resultPath = LocalFileSystem.getInstance().findFileByPath(directoryName)
		}
		return resultPath
	}

	private fun findExistingFileFromAlternatives(base:Path, vararg alternatives:String): Path? {
		for (s in alternatives) {
			val f = base.resolve(s)
			if (Files.exists(f)) {
				return f.toAbsolutePath()
			}
		}
		return null
	}

	private fun javaFromSdk(sdk: Sdk):Path? {
		return findExistingFileFromAlternatives(Paths.get(SDK_TYPE_FOR_WEMI.getBinPath(sdk)), "java", "java.exe")
	}
}

class WindowsShellExecutableEditor(property:KMutableProperty0<String>) : AbstractPropertyEditor<String>(property) {

	private val textField = run {
		val textField = ExtendableTextField()

		// TODO(jp): Use BrowseFolderRunnable if it ever stabilizes
		val action = Runnable {
			val fileChooserDescriptor = FileChooserDescriptor(true, false, false, false, false, false)
			fileChooserDescriptor.title = "Select POSIX Shell"
			fileChooserDescriptor.description = "Select executable of a POSIX compliant shell (for example bash)"

			FileChooser.chooseFile(fileChooserDescriptor, null, textField, null) { chosenFile: VirtualFile? ->
				textField.text = chosenFile?.presentableUrl ?: ""
			}
		}

		val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)
		val browseExtension = ExtendableTextComponent.Extension.create(AllIcons.General.OpenDisk, AllIcons.General.OpenDiskHover, "Browse (" + KeymapUtil.getKeystrokeText(keyStroke) + ")", action)

		object : DumbAwareAction() {
			override fun actionPerformed(e: AnActionEvent) {
				action.run()
			}
		}.registerCustomShortcutSet(CustomShortcutSet(keyStroke), textField)
		textField.addExtension(browseExtension)

		textField
	}

	override val component = JPanel(BorderLayout()).apply {
		border = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1, true), "POSIX Shell executable")
		add(textField, BorderLayout.CENTER)
		add(JBLabel("<html><p>Wemi launcher requires a POSIX compliant shell to run. Unlike on Linux and UNIX systems, Windows does not have it built-in. If you don't have one installed, easiest option is to install <a href=\"https://git-scm.com/download/win\">Git, which comes bundled with it</a>. Alternatively, you can use <a href=\"https://www.msys2.org/\">MSYS2</a> directly, or even the full <a href=\"https://cygwin.com/\">Cygwin</a></p></html>").apply {
			setAllowAutoWrapping(true)
			setCopyable(true)
		}, BorderLayout.SOUTH)
	}
	override var componentValue: String
		get() = textField.text
		set(value) {
			textField.text = value
		}
}

class CommandArgumentEditor(property:KMutableProperty0<List<String>>, title:String = "Java Options") : AbstractPropertyEditor<List<String>>(property) {

	private val splitPattern = Pattern.compile("\\s+")

	override val component: RawCommandLineEditor = RawCommandLineEditor().apply {
		border = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1, true), title)
	}

	override var componentValue: List<String>
		get() {
			val text = component.text.trim()
			return if (text.isBlank()) {
				emptyList()
			} else {
				text.split(splitPattern)
			}
		}
		set(value) {
			component.text = value.joinToString(" ")
		}

}
