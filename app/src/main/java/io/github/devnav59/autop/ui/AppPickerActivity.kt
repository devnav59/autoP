package io.github.devnav59.autop.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.devnav59.autop.databinding.ActivityAppPickerBinding
import io.github.devnav59.autop.databinding.ItemAppBinding
import java.text.Collator
import java.util.Locale

class AppPickerActivity : BaseActivity() {
    private lateinit var binding: ActivityAppPickerBinding
    private lateinit var adapter: AppAdapter
    private var apps: List<AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        adapter = AppAdapter(::selectApp)
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter

        apps = loadLaunchableApps()
        adapter.submit(apps)
        updateEmptyState()
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                adapter.submit(
                    if (query.isBlank()) apps else apps.filter {
                        it.label.contains(query, ignoreCase = true) ||
                            it.packageName.contains(query, ignoreCase = true)
                    },
                )
                updateEmptyState()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun updateEmptyState() {
        binding.emptyState.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun selectApp(app: AppEntry) {
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_PACKAGE, app.packageName)
                putExtra(EXTRA_LABEL, app.label)
            },
        )
        finish()
    }

    private fun loadLaunchableApps(): List<AppEntry> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }
        val collator = Collator.getInstance(Locale("fa"))
        return resolved
            .asSequence()
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                AppEntry(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(packageManager).toString(),
                    icon = runCatching { it.loadIcon(packageManager) }.getOrNull(),
                )
            }
            .sortedWith { first, second -> collator.compare(first.label, second.label) }
            .toList()
    }

    data class AppEntry(val packageName: String, val label: String, val icon: Drawable?)

    private class AppAdapter(
        private val onClick: (AppEntry) -> Unit,
    ) : RecyclerView.Adapter<AppAdapter.Holder>() {
        private var items: List<AppEntry> = emptyList()

        fun submit(newItems: List<AppEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: AppEntry) = with(binding) {
                appName.text = item.label
                packageName.text = item.packageName
                appIcon.setImageDrawable(item.icon)
                root.setOnClickListener { onClick(item) }
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "target_package"
        const val EXTRA_LABEL = "target_label"
    }
}
