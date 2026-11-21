window.__ModuleLoader__.load({
  id: '@dsh-android/repair-profile',
  factory(require) {
    const words = {
      zh: {
        nav: '原 DSH 连接', title: '原 DSH 连接', target: '当前目标', none: '未连接', system: 'System DSH', gecko: 'Gecko DSH',
        phases: { unknown: '状态未知', disconnected: '未连接', checking: '正在检查', authorizing: '等待授权', connecting: '正在连接', connected: '已连接', disconnecting: '正在断开', error: '连接错误', expired: '授权已过期' },
        authorize: '选择目标并连接', manage: '管理当前连接', disconnect: '结束维修', start: '开始修复', starting: '正在创建修复会话…', disconnecting: '正在断开…', authorizing: '正在打开授权…',
        unavailable: '状态暂不可用', requested: '原生授权请求已发送。请在系统页面完成授权。', disconnected: '已请求断开原 DSH。', fail: '操作失败：',
        note: '诊断证据和授权读取的源码可能发送给所选模型。本次任务授权普通修复；特殊敏感操作仍需确认。先后台验证，最后由你主动打开原应用界面。每个修复会话固定绑定一个目标；切换目标后请新建会话。',
      },
      en: {
        nav: 'Original DSH connection', title: 'Original DSH connection', target: 'Current target', none: 'Not connected', system: 'System DSH', gecko: 'Gecko DSH',
        phases: { unknown: 'Status unknown', disconnected: 'Disconnected', checking: 'Checking', authorizing: 'Awaiting authorization', connecting: 'Connecting', connected: 'Connected', disconnecting: 'Disconnecting', error: 'Connection error', expired: 'Authorization expired' },
        authorize: 'Choose target and connect', manage: 'Manage connection', disconnect: 'End repair', start: 'Start repair', starting: 'Creating repair session…', disconnecting: 'Disconnecting…', authorizing: 'Opening authorization…',
        unavailable: 'Status temporarily unavailable', requested: 'Native authorization was requested. Finish authorization on the system screen.', disconnected: 'A disconnect was requested.', fail: 'Action failed: ',
        note: 'Diagnostic evidence and authorized source files may be sent to your selected model. Task authorization covers ordinary repairs; sensitive operations still require confirmation. Validation stays in the background until you explicitly open the target UI. Each repair session is bound to one target; create a new session after switching targets.',
      },
    };
    async function startRepairSession(scope) {
      const workspaces = scope.workspaces.list.getSnapshot();
      const sessions = scope.sessions.list.getSnapshot();
      const items = workspaces.items;
      // Prefer the workspace of the displayed main Session, then the most
      // recently active registered workspace. Never guess a filesystem path.
      const current = sessions.ids.find(id => (sessions.byId[id]?.retainedBy?.mainView ?? 0) > 0);
      let workspace = items.find(item => item.sessionIds.includes(current));
      if (!workspace) workspace = [...items].sort((a, b) => {
        const latest = item => Math.max(Date.parse(item.createdAt) || 0, ...item.sessionIds.map(id => sessions.byId[id]?.updatedAt ?? 0));
        return latest(b) - latest(a);
      })[0];
      if (!workspace) workspace = await scope.workspaces.initializeDefault();
      if (!workspace) throw new Error(scope.locale.bind('workspace')('draft.workspaceRequired'));
      // Always create a new conversation: a reusable blank may already be
      // bound to an old target connection even before its first tool call.
      const sessionId = await scope.sessions.create({ workspaceId: workspace.workspaceId, agentPreset: 'repair' });
      if (scope.remote?.androidRescue?.bindSession) {
        const result = await scope.remote.androidRescue.bindSession(sessionId);
        if (!result?.ok) throw result?.error ?? new Error('REPAIR_SESSION_BIND_FAILED');
      }
      scope.uiWorkspace.openSession(sessionId);
      return sessionId;
    }
    const showFeedback = (message, requested, phase) => !!message && (message !== requested || ['checking', 'authorizing', 'connecting'].includes(phase));
    return { startRepairSession, showFeedback, inject: ['remote'], async apply(ctx) {
      // rc.5 requires explicit Client selection of each Host Remote contribution.
      // There are no parameters; the native target and authority remain Host-owned.
      await ctx.remote.$mount({
        package: '@dsh-android/repair-profile',
        descriptors: ['authorize', 'disconnect', 'status', 'validateUi', 'themeSnapshot', 'bindSession'].map(method => ({
          id: `@dsh-android/repair-profile#${method}`, service: 'androidRescue', namespace: 'androidRescue', method,
          parameters: ['themeSnapshot','bindSession'].includes(method) ? [{name:method === 'themeSnapshot' ? 'theme' : 'sessionId',wire:method === 'themeSnapshot' ? 'theme' : 'sessionId',source:'json',codec:{mode:'strict',typeSymbol:`@dsh-android/repair-profile/${method === 'themeSnapshot' ? 'theme' : 'sessionId'}`,create:()=>({parse:value=>{
             if (method === 'bindSession') { if(typeof value!=='string'||!value.trim()||value.length>256)throw new Error('REPAIR_SESSION_INVALID'); return value; }
             if(!value||typeof value.dark!=='boolean'||Object.keys(value).sort().join(',')!=='colors,dark'||!value.colors||Object.keys(value.colors).sort().join(',')!=='background,primary,surface,text'||!Object.values(value.colors).every(color=>typeof color==='string'&&/^#[0-9a-fA-F]{6}$/.test(color)))throw new Error('REPAIR_THEME_INVALID');
             return value;
           }})}}] : [], result: { mode: 'src-json' }, invocation: { kind: 'direct' },
        })),
      });
      ctx.inject(['slots', 'locale', 'remote', 'remote.androidRescue', 'uiWorkspace', 'sessions', 'workspaces'], scope => {
        const React = require('react');
        const h = React.createElement;
        scope.effect(() => scope.locale.register('dsh-android-rescue', Object.fromEntries(Object.entries(words).map(([locale, entries]) => {
          const { phases, ...flat } = entries;
          return [locale, { ...flat, ...Object.fromEntries(Object.entries(phases).map(([phase, label]) => [`phases.${phase}`, label])) }];
        }))));
        const t = scope.locale.bind('dsh-android-rescue');
        function captureTheme() {
          const style = getComputedStyle(document.documentElement);
          const color = (token, fallback) => {
            const probe = document.createElement('span');
            probe.style.color = `var(${token}, ${fallback})`; document.body.appendChild(probe);
            const rgb = getComputedStyle(probe).color.match(/^rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)$/);
            probe.remove();
            return rgb ? '#' + rgb.slice(1).map(v => Number(v).toString(16).padStart(2, '0')).join('') : fallback;
          };
          const background = color('--dsw-alias-bg-layer-1', '#ffffff');
          const surface = color('--dsw-alias-bg-layer-2', '#f5f5f5');
          const text = color('--dsw-alias-label-primary', '#111111');
          const primary = color('--dsw-alias-button-primary-fill', '#2458b6');
          const dark = parseInt(background.slice(1,3),16) + parseInt(background.slice(3,5),16) + parseInt(background.slice(5,7),16) < 384;
          return { dark, colors: { background, surface, text, primary } };
        }
        function Rescue({ close = () => {}, compact = false }) {
          const [status, setStatus] = React.useState(null);
          const [busy, setBusy] = React.useState('');
          const [message, setMessage] = React.useState('');
          const [expanded, setExpanded] = React.useState(false);
          const pending = React.useRef(false);
          const mounted = React.useRef(true);
          React.useEffect(() => {
            mounted.current = true;
            let stopped = false;
            const refresh = async () => {
              if (pending.current) return;
              pending.current = true;
              let timeout;
              try {
                const result = await Promise.race([
                  scope.remote.androidRescue.status(),
                  new Promise((_, reject) => { timeout = setTimeout(() => reject(new Error('STATUS_TIMEOUT')), 8000); }),
                ]);
                if (!stopped) setStatus(result?.ok ? result.value : null);
              }
              catch { if (!stopped) setStatus(null); }
              finally { clearTimeout(timeout); pending.current = false; }
            };
            void refresh();
            const timer = setInterval(refresh, 1800);
            return () => { stopped = true; mounted.current = false; clearInterval(timer); };
          }, []);
          const run = async (action) => {
            if (busy) return;
            setBusy(action); setMessage('');
            try {
              if (action === 'start') {
                if (status?.phase !== 'connected') throw new Error('TARGET_NOT_CONNECTED');
                await startRepairSession(scope);
                close();
              } else {
                try { await scope.remote.androidRescue.themeSnapshot(captureTheme()); } catch { /* Native retains its validated last/system theme. */ }
                const result = await scope.remote.androidRescue[action]();
                if (!result?.ok) throw result?.error ?? new Error('request_failed');
                setMessage(action === 'validateUi' ? '已请求打开原 DSH 验证界面；请返回查看本轮结果。' : t(action === 'authorize' ? 'requested' : 'disconnected'));
              }
            } catch (error) { if (mounted.current) setMessage(t('fail') + String(error?.message ?? error)); }
            finally { if (mounted.current) setBusy(''); }
          };
          const connected = status?.phase === 'connected';
          const task = status?.task, attempt = task?.last_attempt;
          const phaseLabels = { editable:'维修中', validating: attempt?.level === 'syntax' ? '后台检查语法中' : '后台验证引擎中', committing:'正在提交修改', ending:'正在结束维修', recovery_required:'需要恢复处理', maintenance:'维修中', repairing:'维修中', syntax_validating:'检查语法中', engine_validating:'后台验证引擎中', awaiting_ui:'检查通过，可回原 DSH 重启验证', ui_validating:'界面验证中', complete:'本轮验证通过', failed:'验证失败', ended:'维修已结束' };
          const taskLabel = task ? (phaseLabels[task.phase] ?? task.phase) : '';
                    const nativeBusy = ['checking','authorizing','connecting','disconnecting'].includes(status?.phase);
          React.useEffect(() => {
            if (status?.phase === 'connected' || status?.phase === 'disconnected') setMessage(previous =>
              previous === t('requested') || previous === t('disconnected') ? '' : previous);
          }, [status?.phase]);
          const label = status?.target === 'com.dsh.android' ? t('system') : status?.target === 'com.dsh.webview' ? t('gecko') : t('none');
          const button = (action, text, primary = false) => h('button', {
            type: 'button', disabled: !!busy || nativeBusy || (action === 'start' && !connected), onClick: () => void run(action),
            style: { minHeight: '48px', padding: '8px 14px', border: '1px solid var(--dsw-alias-border-l3)', borderRadius: '6px', background: primary ? 'var(--dsw-alias-button-primary-fill)' : 'var(--dsw-alias-bg-layer-2)', color: primary ? 'var(--dsw-alias-label-primary-foreground)' : 'var(--dsw-alias-label-primary)', cursor: busy ? 'wait' : 'pointer', opacity: (busy || nativeBusy || (action === 'start' && !connected)) ? .55 : 1 },
          }, busy === action ? t(action === 'start' ? 'starting' : action === 'disconnect' ? 'disconnecting' : 'authorizing') : text);
          const detail = h('section', { style: { color: 'var(--dsw-alias-label-primary)', display: 'grid', gap: '14px', maxWidth: '720px' } },
            h('h2', { style: { margin: 0, fontSize: '18px', fontWeight: 600 } }, t('title')),
            h('div', { style: { display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', overflowWrap: 'anywhere', gap: '8px 16px', padding: '12px 0', borderTop: '1px solid var(--dsw-alias-border-l3)', borderBottom: '1px solid var(--dsw-alias-border-l3)' } },
              h('span', { style: { color: 'var(--dsw-alias-label-secondary)' } }, t('target')),
              h('span', null, `${label} · ${t(`phases.${status?.phase ?? 'unknown'}`)}`),
              status?.message ? h('span', { style: { gridColumn: '1 / -1', color: 'var(--dsw-alias-label-secondary)', lineHeight: 1.5 } }, status.message) : null),
            h('div', { style: { display: 'flex', flexWrap: 'wrap', gap: '8px' } },
              button('authorize', t(connected ? 'manage' : 'authorize')),
              connected ? button('disconnect', t('disconnect')) : null,
              button('start', t('start'), true)),
            task ? h('div', { role:'status', 'aria-live':'polite', style:{lineHeight:1.6,overflowWrap:'anywhere'} },
              h('strong', null, taskLabel),
              task.message ? h('p', { role:'status', 'aria-live':'polite' }, task.message) : null,
              h('p', null, `任务 ${task.task_id} · 修改轮次 ${task.change_revision}`),
              attempt ? h('p', null, `${attempt.level ?? ''} · ${attempt.status ?? ''} · ${attempt.attempt_id ?? ''}`) : null,
              attempt?.summary ? h('p', null, attempt.summary) : null,
              task.last_operation ? h('p', null, `文件操作 ${task.last_operation.kind ?? ''} · ${task.last_operation.status ?? ''}`) : null,
              status.evidence?.summary ? h('p', null, status.evidence.summary) : null,
              status.evidence?.error_summary ? h('p', null, status.evidence.error_summary) : null,
              status.evidence?.message || status.evidence?.safe_stack ? h('pre', {style:{whiteSpace:'pre-wrap',maxHeight:'160px',overflow:'auto'}}, [status.evidence.message,status.evidence.safe_stack].filter(Boolean).join('\n')) : null,
              h('p', null, '后台检查通过后回原 DSH 点「重启并验证」；重启后的实际启动是最终验收。结束维修会保留已应用的修改并恢复普通启动。')) : null,
            showFeedback(message, t('requested'), status?.phase) ? h('p', { role: 'status', style: { margin: 0, lineHeight: 1.5 } }, message) : null,
            !status ? h('p', { role: 'status', style: { margin: 0, color: 'var(--dsw-alias-label-secondary)' } }, t('unavailable')) : null,
            h('p', { style: { margin: 0, fontSize: '13px', color: 'var(--dsw-alias-label-secondary)', lineHeight: 1.55 } }, t('note')));
          if (!compact) return detail;
          return h(React.Fragment, null,
            h('button', {type:'button', onClick:()=>setExpanded(true), 'aria-label':'维修状态', style:{minHeight:'36px',maxWidth:'180px',overflow:'hidden',textOverflow:'ellipsis',whiteSpace:'nowrap',border:'1px solid var(--dsw-alias-border-l3)',borderRadius:'6px',background:'var(--dsw-alias-bg-layer-2)',color:'var(--dsw-alias-label-primary)',padding:'4px 8px'}}, task ? `维修 · ${taskLabel}` : `连接 · ${t(`phases.${status?.phase ?? 'unknown'}`)}`),
            expanded ? require('react-dom').createPortal(h('div', {role:'dialog','aria-modal':true,'aria-label':'维修状态详情',style:{position:'fixed',inset:0,zIndex:10000,background:'rgba(0,0,0,.4)',display:'flex',alignItems:'center',justifyContent:'center',padding:'16px'},onClick:()=>setExpanded(false)},
              h('div',{style:{background:'var(--dsw-alias-bg-layer-1)',padding:'20px',borderRadius:'12px',maxWidth:'760px',width:'100%',maxHeight:'90vh',overflow:'auto'},onClick:event=>event.stopPropagation()},
                h('button',{type:'button',onClick:()=>setExpanded(false),style:{float:'right',minHeight:'40px',color:'var(--dsw-alias-label-primary)',background:'var(--dsw-alias-bg-layer-2)',border:'1px solid var(--dsw-alias-border-l3)'}},'关闭'), detail)),document.body) : null);
        }
        // rc.5's blank-session landing row places hero.options after the workspace/preset,
        // right-aligned by the official shell. Keep the active-session header entry too.
        for (const name of ['conversation.hero.options', 'conversation.session.header.utilities']) {
          scope.slots.inject(name, () => scope.slots.register({name,id:'dsh-android-rescue-state',order:80}, () => h(Rescue,{compact:true})));
        }
        scope.slots.inject('settings.section', () => scope.slots.register({ name: 'settings.section', id: 'dsh-android-rescue', order: 24, locale: 'dsh-android-rescue', label: () => t('nav') }, Rescue));
      });
    } };
  },
});
