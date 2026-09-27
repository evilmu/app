package br.gov.bomsucesso.threadsdownloader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

/**
 * Settings / diagnostics only. Downloads happen with one tap inside each
 * eligible post in the original Threads app, never through a pasted URL here.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var moduleStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = "Threads Inline Download"
            textSize = 26f
        })
        root.addView(TextView(this).apply {
            text = "Este módulo adiciona ↓ à barra de ações de cada publicação " +
                "com vídeo ou imagem, dentro do Threads original. " +
                "Toque no ícone para iniciar o download daquela publicação. " +
                "Os arquivos aparecem em Downloads/Threads."
            textSize = 16f
            setPadding(0, padding, 0, padding)
        })
        moduleStatus = TextView(this).apply { textSize = 16f }
        root.addView(moduleStatus)
        root.addView(Button(this).apply {
            text = "Abrir o Threads"
            setOnClickListener {
                val intent = packageManager.getLaunchIntentForPackage("com.instagram.barcelona")
                if (intent != null) startActivity(intent)
                else moduleStatus.text = "Threads não localizado. Confirme a instalação do aplicativo oficial."
            }
        })
        root.addView(Button(this).apply {
            text = "Atualizar diagnóstico"
            setOnClickListener { showStatus() }
        })
        root.addView(TextView(this).apply {
            text = "Ative somente este módulo no ReLSPosed para com.instagram.barcelona " +
                "e reinicie o Threads. Se o botão não aparecer, abra os logs do ReLSPosed " +
                "e procure por ThreadsInline. Desative as versões anteriores do módulo."
            textSize = 14f
            setPadding(0, padding, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
        showStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::moduleStatus.isInitialized) showStatus()
    }

    private fun showStatus() {
        val ready = getSharedPreferences("hook_status", Context.MODE_PRIVATE)
            .getLong("ready_at", 0L)
        moduleStatus.text = if (ready == 0L)
            "Módulo ainda não detectado no Threads. Verifique o escopo do ReLSPosed."
        else "Hook carregado no Threads em " +
            DateFormat.getDateTimeInstance().format(Date(ready)) +
            ". Esta confirmação não substitui a verificação do botão no feed."
    }
}
