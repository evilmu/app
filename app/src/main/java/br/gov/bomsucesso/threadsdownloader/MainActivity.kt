package br.gov.bomsucesso.threadsdownloader

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import br.gov.bomsucesso.threadsdownloader.storage.CapturedUrlStore
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var contentHost: FrameLayout
    private var selectedTab = Tab.HOME

    private val settings by lazy { getSharedPreferences("module_settings", MODE_PRIVATE) }
    private val runtime by lazy { getSharedPreferences("runtime_status", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(0, 72, 34)
        window.navigationBarColor = Color.rgb(12, 15, 13)
        requestNotificationPermission()
        setContentView(buildApp())
        handleSharedText(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::contentHost.isInitialized) renderSelectedTab()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedText(intent)
    }

    private fun buildApp(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 15, 13))
        }

        root.addView(buildHeader(), LinearLayout.LayoutParams.MATCH_PARENT, dp(78))

        contentHost = FrameLayout(this)
        root.addView(contentHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        root.addView(buildBottomNavigation(), LinearLayout.LayoutParams.MATCH_PARENT, dp(82))
        renderSelectedTab()
        return root
    }

    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(14), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(0, 75, 31), Color.rgb(0, 112, 47))
            )

            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
            }, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(12) })

            addView(TextView(this@MainActivity).apply {
                text = "Threads Enhancer"
                setTextColor(Color.WHITE)
                textSize = 27f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

            addView(ImageView(this@MainActivity).apply {
                setImageResource(android.R.drawable.ic_menu_info_details)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener {
                    Toast.makeText(this@MainActivity, "Threads Enhancer ${BuildConfig.VERSION_NAME}", Toast.LENGTH_LONG).show()
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
    }

    private fun buildBottomNavigation(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(Color.rgb(17, 20, 18))
            addView(navItem("Geral", android.R.drawable.ic_menu_preferences, Tab.GENERAL), navParams())
            addView(navItem("Início", android.R.drawable.ic_menu_view, Tab.HOME), navParams())
            addView(navItem("Mídia", android.R.drawable.ic_menu_gallery, Tab.MEDIA), navParams())
        }
    }

    private fun navParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)

    private fun navItem(label: String, icon: Int, tab: Tab): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(4), dp(4), dp(2))

            val image = ImageView(this@MainActivity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(if (selectedTab == tab) GREEN else Color.LTGRAY)
            }
            addView(image, LinearLayout.LayoutParams(dp(30), dp(30)))

            val title = TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                setTextColor(if (selectedTab == tab) GREEN else Color.LTGRAY)
                typeface = if (selectedTab == tab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            addView(title)

            setOnClickListener {
                selectedTab = tab
                val parent = parent as? LinearLayout
                parent?.let {
                    val index = it.indexOfChild(this)
                    rootView.findViewById<View>(android.R.id.content)
                    setContentView(buildApp())
                }
            }
        }
    }

    private fun renderSelectedTab() {
        contentHost.removeAllViews()
        val view = when (selectedTab) {
            Tab.HOME -> buildHome()
            Tab.GENERAL -> buildGeneral()
            Tab.MEDIA -> buildMedia()
        }
        contentHost.addView(view)
    }

    private fun buildHome(): View {
        val container = pageContainer()
        container.addView(sectionTitle("Início"))

        val hookActive = isHookActive()
        container.addView(statusCard(
            title = if (hookActive) "Módulo ativado" else "Aguardando ativação",
            subtitle = if (hookActive) "Versão ${BuildConfig.VERSION_NAME} · hook detectado" else "Ative no LSPosed e abra o Threads",
            positive = hookActive
        ))

        val threadsVersion = installedVersion("com.instagram.barcelona")
        container.addView(statusCard(
            title = if (threadsVersion != null) "Threads detectado" else "Threads não encontrado",
            subtitle = threadsVersion?.let { "Versão $it" } ?: "Instale o Threads oficial",
            positive = threadsVersion != null
        ))

        container.addView(statusCard(
            title = "Escopo recomendado configurado",
            subtitle = "com.instagram.barcelona",
            positive = true
        ))

        container.addView(infoCard())

        container.addView(Button(this).apply {
            text = "ABRIR LSPOSED"
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(0, 115, 47))
            setOnClickListener { openLsposed() }
        }, marginParams())

        return scroll(container)
    }

    private fun buildGeneral(): View {
        val container = pageContainer()
        container.addView(sectionTitle("Geral"))
        container.addView(category("Menu do Threads"))
        container.addView(settingSwitch(
            "Mostrar botão Baixar",
            "Adiciona a ação de download no menu da publicação",
            "download_enabled",
            true
        ))
        container.addView(settingSwitch(
            "Mostrar Opções de download",
            "Permite escolher entre as mídias detectadas",
            "options_enabled",
            true
        ))

        container.addView(category("Downloads"))
        container.addView(settingSwitch(
            "Permitir vídeos",
            "Captura e baixa vídeos carregados pelo Threads",
            "videos_enabled",
            true
        ))
        container.addView(settingSwitch(
            "Permitir imagens",
            "Captura e baixa imagens carregadas pelo Threads",
            "images_enabled",
            true
        ))
        container.addView(settingSwitch(
            "Mostrar notificação",
            "Exibe o progresso e a conclusão do download",
            "notifications_enabled",
            true
        ))

        return scroll(container)
    }

    private fun buildMedia(): View {
        val container = pageContainer()
        container.addView(sectionTitle("Mídia"))

        val captured = CapturedUrlStore.read(this)
        container.addView(darkCard().apply {
            val inside = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                addView(TextView(this@MainActivity).apply {
                    text = "Última mídia detectada"
                    textSize = 19f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = captured?.substringBefore('?') ?: "Nenhuma mídia detectada ainda"
                    textSize = 14f
                    setTextColor(Color.LTGRAY)
                    setPadding(0, dp(8), 0, dp(12))
                })
                addView(Button(this@MainActivity).apply {
                    text = "BAIXAR ÚLTIMA MÍDIA"
                    isEnabled = !captured.isNullOrBlank()
                    setOnClickListener { captured?.let { enqueueDownload(it) } }
                })
                addView(Button(this@MainActivity).apply {
                    text = "COPIAR LINK"
                    isEnabled = !captured.isNullOrBlank()
                    setOnClickListener {
                        captured?.let {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Mídia do Threads", it))
                            Toast.makeText(this@MainActivity, "Link copiado", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
            addView(inside)
        }, marginParams())

        container.addView(darkCard().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                addView(TextView(this@MainActivity).apply {
                    text = "Pasta de destino"
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Download/Threads"
                    textSize = 16f
                    setTextColor(GREEN)
                    setPadding(0, dp(8), 0, 0)
                })
            })
        }, marginParams())

        return scroll(container)
    }

    private fun statusCard(title: String, subtitle: String, positive: Boolean): View {
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(if (positive) Color.rgb(42, 157, 64) else Color.rgb(91, 73, 28))
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(17), dp(20), dp(17))
                addView(TextView(this@MainActivity).apply {
                    text = if (positive) "✓" else "!"
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = 20f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = subtitle
                        textSize = 15f
                        setTextColor(Color.WHITE)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(row)
        }.also { it.layoutParams = marginParams() }
    }

    private fun infoCard(): View {
        return darkCard().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(18))
                addInfoLine("Versão do sistema (API)", Build.VERSION.SDK_INT.toString())
                addInfoLine("Fabricante", Build.MANUFACTURER)
                addInfoLine("Modelo do dispositivo", Build.MODEL)
                addInfoLine("Pasta de downloads", "Download/Threads")
            })
        }.also { it.layoutParams = marginParams() }
    }

    private fun LinearLayout.addInfoLine(label: String, value: String) {
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(7), 0, 0)
        })
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun settingSwitch(title: String, description: String, key: String, default: Boolean): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(14))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 18f
                    setTextColor(Color.WHITE)
                })
                addView(TextView(this@MainActivity).apply {
                    text = description
                    textSize = 14f
                    setTextColor(Color.LTGRAY)
                    setPadding(0, dp(4), dp(8), 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(SwitchMaterial(this@MainActivity).apply {
                isChecked = settings.getBoolean(key, default)
                buttonTintList = null
                setOnCheckedChangeListener { _, checked -> settings.edit().putBoolean(key, checked).apply() }
            })
        }
    }

    private fun category(value: String): View = TextView(this).apply {
        text = value
        textSize = 17f
        setTextColor(GREEN)
        setPadding(dp(20), dp(22), dp(20), dp(8))
    }

    private fun sectionTitle(value: String): View = TextView(this).apply {
        text = value
        textSize = 28f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(20), dp(22), dp(20), dp(12))
    }

    private fun pageContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), 0, dp(8), dp(28))
    }

    private fun darkCard() = MaterialCardView(this).apply {
        radius = dp(22).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(Color.rgb(34, 37, 35))
    }

    private fun scroll(content: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(content)
    }

    private fun marginParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        setMargins(dp(12), dp(8), dp(12), dp(8))
    }

    private fun isHookActive(): Boolean {
        val lastSeen = runtime.getLong("last_hook_active", 0L)
        return lastSeen > 0L && System.currentTimeMillis() - lastSeen < 24 * 60 * 60 * 1000L
    }

    private fun installedVersion(packageName: String): String? = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull()

    private fun openLsposed() {
        val packages = listOf("org.lsposed.manager", "com.android.shell")
        val intent = packages.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        if (intent != null) startActivity(intent)
        else Toast.makeText(this, "Abra o LSPosed pelo gerenciador", Toast.LENGTH_LONG).show()
    }

    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val url = Regex("""https://[^\s]+""").find(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())?.value
        if (url != null) {
            CapturedUrlStore.save(this, url)
            selectedTab = Tab.MEDIA
            if (::contentHost.isInitialized) setContentView(buildApp())
        }
    }

    private fun enqueueDownload(url: String) {
        runCatching {
            require(url.startsWith("https://")) { "URL inválida" }
            val video = url.contains(".mp4", true) || url.contains("video", true)
            val extension = if (video) ".mp4" else ".jpg"
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(if (video) "Vídeo do Threads" else "Imagem do Threads")
                .setDescription("Download em andamento")
                .setMimeType(if (video) "video/mp4" else "image/jpeg")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "Threads/threads_${System.currentTimeMillis()}$extension"
                )
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Download iniciado", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Erro: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class Tab { GENERAL, HOME, MEDIA }

    companion object {
        private val GREEN = Color.rgb(88, 214, 100)
    }
}
