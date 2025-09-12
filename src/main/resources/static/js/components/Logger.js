/** Лёгкий логгер для консоли. Поддерживает уровни. */
export class Logger {
    info(msg, ...args)   { console.info('[ИНФО]', msg, ...args); }
    ok(msg, ...args)     { console.log('[ОК]', msg, ...args); }
    warn(msg, ...args)   { console.warn('[ПРЕДУПРЕЖДЕНИЕ]', msg, ...args); }
    error(msg, ...args)  { console.error('[ОШИБКА]', msg, ...args); }
}
