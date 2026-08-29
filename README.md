# Threads Downloader LSPosed

Protótipo Android para capturar URLs de mídia carregadas pelo aplicativo Threads e baixar URLs HTTPS diretas.

## Requisitos

- Android Studio recente
- JDK 17
- Android SDK 35
- Magisk com Zygisk
- LSPosed
- Threads: `com.instagram.barcelona`

## Compilar

1. Abra a pasta no Android Studio.
2. Aguarde a sincronização do Gradle.
3. Execute `Build > Build APK(s)`.
4. Instale o APK gerado.
5. Abra o LSPosed.
6. Ative **Threads Downloader**.
7. Marque somente o aplicativo Threads no escopo.
8. Force a parada do Threads e abra novamente.

## Funcionamento desta versão

- A versão 3.6.0 injeta **Baixar** e **Opções de download** no menu nativo de
  três pontos do Threads 439.1.0.43.89.
- A detecção usa o contexto exato da publicação e possui uma segunda rota pelo
  item **Copiar link** quando o ReLSPosed não intercepta o wrapper do menu.
- O hook observa URLs criadas por `java.net.URL` e `android.net.Uri.parse` para
  localizar a mídia carregada pelo Threads.
- O app usa o `DownloadManager` para salvar os arquivos em `Downloads/Threads`.

## Limitações

O Threads pode:

- usar bibliotecas de rede próprias ou ofuscadas;
- usar DASH/HLS com áudio e vídeo separados;
- gerar URLs temporárias;
- exigir cookies, tokens ou cabeçalhos;
- deixar de passar URLs pelos métodos interceptados.

## Uso responsável

Use para baixar conteúdo próprio, autorizado ou cuja licença permita o download. Não contorne conteúdo privado, controles de acesso, DRM ou autenticação.
