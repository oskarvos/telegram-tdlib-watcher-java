package com.oleg.td;

import io.javalin.Javalin;

public class WebApp {
    public static void main(String[] args) {
        // Подключаем перехват stdout/stderr ДО запуска бота/сервера
        LogBootstrap.install();

        BotService service = new BotService();

        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false);

        // Главная (как раньше)
        app.get("/", ctx -> {
            var state = service.getState();
            var chats = service.getWatchedChats();
            String chatList = (chats == null || chats.isEmpty()) ? "(пока нет)" : chats.toString();
            ctx.html("""
                <html><head><meta charset="utf-8"><title>TD Userbot</title></head>
                <body style="font-family:sans-serif;padding:20px">
                  <h2>TD Userbot</h2>
                  <p>Состояние: <b>%s</b></p>
                  <form method="post" action="/start"><button %s>Старт</button></form>
                  <form method="post" action="/stop" style="margin-top:10px"><button %s>Стоп</button></form>
                  <p style="margin-top:10px"><a href="/logs">Открыть логи</a></p>
                  <hr/>
                  <h3>Чаты</h3>
                  <p>Наблюдаемые: %s</p>
                  <form method="post" action="/dump" style="margin-top:10px">
                    <label>chatId: <input name="chatId"/></label>
                    <button>Dump now</button>
                  </form>
                </body></html>
            """.formatted(
                    state,
                    (service.isRunning() ? "disabled" : ""),
                    (!service.isRunning() ? "disabled" : ""),
                    chatList
            ));
        });

        // Страница консоли логов
        app.get("/logs", ctx -> ctx.html("""
            <html><head><meta charset="utf-8"><title>Logs</title>
            <style>
              body{font-family:monospace;margin:0}
              #top{padding:8px;background:#f5f5f5;position:sticky;top:0;border-bottom:1px solid #ddd}
              #log{white-space:pre-wrap;padding:10px}
              button{margin-right:8px}
            </style></head>
            <body>
              <div id="top">
                <button id="clear">Очистить</button>
                <label><input type="checkbox" id="autoscroll" checked/> автопрокрутка</label>
                <span id="status" style="margin-left:10px;color:#666">подключение…</span>
              </div>
              <div id="log"></div>
              <script>
                const log = document.getElementById('log');
                const status = document.getElementById('status');
                const autoscroll = document.getElementById('autoscroll');

                function scrollDown(){ if(autoscroll.checked) window.scrollTo(0, document.body.scrollHeight); }

                const es = new EventSource('/logs/stream');
                es.onopen = () => status.textContent = 'подключено';
                es.onerror = () => status.textContent = 'ошибка соединения';
                es.addEventListener('log', e => {
                  log.textContent += e.data + "\\n";
                  scrollDown();
                });

                document.getElementById('clear').onclick = async () => {
                  await fetch('/logs/clear', {method:'POST'});
                  log.textContent = '';
                };
              </script>
            </body></html>
        """));

        // SSE-стрим логов
        app.sse("/logs/stream", client -> LogHub.get().addClient(client));

        // Очистка буфера логов
        app.post("/logs/clear", ctx -> { LogHub.get().clear(); ctx.result("ok"); });

        // Остальные хендлеры (как раньше)
        app.post("/start", ctx -> {
            if (!service.isRunning()) {
                try { service.start(); ctx.result("Started"); }
                catch (Exception e) { ctx.status(500).result("Start failed: " + e.getMessage()); }
            } else ctx.result("Already running");
        });

        app.post("/stop", ctx -> { service.close(); ctx.result("Stopped"); });

        app.post("/dump", ctx -> {
            String s = ctx.formParam("chatId");
            if (s == null || s.isBlank()) { ctx.status(400).result("chatId required"); return; }
            long chatId;
            try { chatId = Long.parseLong(s.trim()); } catch (Exception e) { ctx.status(400).result("bad chatId"); return; }
            boolean ok = service.dumpNow(chatId);
            ctx.result(ok ? "Dump finished" : "Dump failed or no client");
        });

        app.events(ev -> ev.serverStopped(service::close));
        app.start(8080);
    }
}
