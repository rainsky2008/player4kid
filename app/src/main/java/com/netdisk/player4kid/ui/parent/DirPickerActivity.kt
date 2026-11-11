package com.netdisk.player4kid.ui.parent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.netdisk.player4kid.App
import com.netdisk.player4kid.R
import com.netdisk.player4kid.baidu.BaiduApi
import com.netdisk.player4kid.baidu.PanFile
import com.netdisk.player4kid.databinding.ActivityDirPickerBinding
import com.netdisk.player4kid.databinding.ItemRowBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 选择网盘目录，作为孩子可以看的资源目录 */
class DirPickerActivity : AppCompatActivity() {

    private lateinit var b: ActivityDirPickerBinding
    private var path = "/"
    private var items: List<PanFile> = emptyList()
    private var job: Job? = null
    private val adapter = Adapter()
    /** 应用只能访问 /apps/应用名 */
    private var scopeLimited = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDirPickerBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter
        b.btnUp.setOnClickListener { goUp() }
        b.btnInput.setOnClickListener { inputPath() }
        b.btnCancel.setOnClickListener { finish() }
        b.btnSelect.setOnClickListener {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PATH, path))
            finish()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (path == "/") finish() else goUp()
            }
        })
        open("/")
    }

    private fun inputPath() {
        val edit = EditText(this).apply {
            setText(if (path == "/") "/apps/" else path)
            setSingleLine()
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle("输入网盘目录路径")
            .setMessage("例如 /apps/你的应用名/动画片")
            .setView(box)
            .setPositiveButton("打开") { _, _ ->
                val p = "/" + edit.text.toString().trim().trim('/')
                open(p)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun goUp() {
        if (path == "/") return
        open(path.substringBeforeLast('/').ifEmpty { "/" })
    }

    private fun open(dir: String) {
        path = dir
        b.txtPath.text = dir
        b.btnSelect.isEnabled = dir != "/"
        b.progress.visibility = View.VISIBLE
        b.txtMessage.visibility = View.GONE
        items = emptyList()
        adapter.notifyDataSetChanged()
        job?.cancel()
        job = lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { App.api.listDir(dir, foldersOnly = true, useCache = false) }
            }
            b.progress.visibility = View.GONE
            r.onSuccess {
                items = it.filter { f -> f.isDir }
                adapter.notifyDataSetChanged()
                if (items.isEmpty()) {
                    b.txtMessage.visibility = View.VISIBLE
                    b.txtMessage.text = "没有子目录\n可以直接选择当前目录"
                }
            }.onFailure {
                val errno = (it as? BaiduApi.ApiException)?.errno
                if (dir == "/" && (errno == -7 || errno == 20020)) {
                    // 新建的开放平台应用只能访问 /apps/应用名，自动跳过去
                    scopeLimited = true
                    open("/apps")
                    return@launch
                }
                b.txtMessage.visibility = View.VISIBLE
                b.txtMessage.text = "加载失败：${it.message}"
            }
            b.txtHint.visibility = if (scopeLimited) View.VISIBLE else View.GONE
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val v: ItemRowBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = items[position]
            holder.v.imgIcon.setImageResource(R.drawable.ic_folder)
            holder.v.txtName.text = f.name
            holder.v.root.setOnClickListener { open(f.path) }
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
