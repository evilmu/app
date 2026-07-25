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

- O hook observa URLs criadas por `java.net.URL` e `android.net.Uri.parse`.
- URLs prováveis de mídia são registradas no log do LSPosed.
- O app aceita URL HTTPS direta e usa o `DownloadManager`.
- O app também aparece no menu Compartilhar para textos e links.

## Limitações

O Threads pode:

- usar bibliotecas de rede próprias ou ofuscadas;
- usar DASH/HLS com áudio e vídeo separados;
- gerar URLs temporárias;
- exigir cookies, tokens ou cabeçalhos;
- deixar de passar URLs pelos métodos interceptados.

## Uso responsável

Use para baixar conteúdo próprio, autorizado ou cuja licença permita o download. Não contorne conteúdo privado, controles de acesso, DRM ou autenticação.
