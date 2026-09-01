/**
 * Every user-visible string in DBX lives here. No component hard-codes copy.
 *
 * The Chinese wording is domain language, not presentation: each term below is the
 * `_中文_` line of the matching entry in the repository's `CONTEXT.md`. When a word is
 * missing, add it to `CONTEXT.md` first — do not invent a synonym here.
 *
 * There is no i18n framework in this phase (see #30); this module is its input.
 */
export const messages = {
  product: {
    name: 'DBX',
    /** `Migration task` — `_Avoid_: Job`. The navigation must never read 「作业中心」. */
    tagline: '数据库迁移控制台',
  },
  nav: {
    ariaLabel: '主导航',
    migrationTasks: '迁移任务',
    /**
     * `Data source management` — the navigation area, whose `_中文_` is 数据源. An
     * individual endpoint is a 数据库连接; the two are different concepts, which is why
     * `Database connection` still lists `datasource` under `_Avoid_`.
     */
    databaseConnections: '数据源',
    settings: '系统设置',
  },
  tasks: {
    title: '迁移任务',
    lead: '这里列出全部迁移任务与尚未批准的迁移草稿。',
  },
  wizard: {
    title: '新建迁移草稿',
    stageLabel: '阶段',
    draftLabel: '草稿',
    stages: {
      connections: '连接与数据库',
      scope: '迁移范围',
      tables: '逐表配置与预检',
      confirm: '执行确认',
      monitor: '运行监控',
      validation: '校验报告',
    },
  },
  run: {
    title: '迁移运行',
    runLabel: '运行',
    unitLabel: '表迁移单元',
    evidenceTitle: '表迁移单元证据',
  },
  connections: {
    title: '数据源',
    lead: '数据库连接与凭据版本的登记、校验与维护都在这里进行。',
    /**
     * The list is a list of 数据库连接. 数据源 names the navigation area only — an
     * individual endpoint is never called a 数据源, which is why `Database connection`
     * still lists `datasource` under `_Avoid_`.
     */
    listLabel: '数据库连接',
    roles: { source: '源', target: '目标' },
    sourceDialectLabel: '源方言',
    targetDialectLabel: '目标方言',
    endpointLabel: '端点',
    credentialVersionLabel: '凭据版本',
    latestCheckLabel: '最近校验',
    neverChecked: '尚未校验',
    /**
     * A connection check has no `_中文_` wording for its outcomes in `CONTEXT.md`, so the
     * interface shows the literal rather than inventing a translation — the precedent
     * batch 1 set for preflight conclusions.
     */
    checkOutcomes: { succeeded: 'SUCCEEDED', failed: 'FAILED', notRun: 'NOT_RUN' },
    recheckAction: '重新校验',
    /** Credential versions are immutable, so maintaining one adds a version. */
    addCredentialVersionAction: '新建凭据版本',
    registerAction: '登记数据库连接',
    loading: '正在读取数据库连接。',
    empty: {
      title: '尚未登记任何数据库连接',
      body: '下一步：登记源 MySQL 与目标 PostgreSQL 的数据库连接。迁移向导只能从这里已登记的数据库连接中选择源与目标。',
    },
    error: {
      title: '数据库连接读取失败',
      body: '这次请求没有成功。稍后重试，或确认 DBX 后端是否可达。',
    },
    register: {
      title: '登记数据库连接',
      description: '凭据只在数据源里录入；迁移向导不提供录入凭据的入口。',
      nameLabel: '名称',
      roleLabel: '用途',
      hostLabel: '主机',
      portLabel: '端口',
      databaseLabel: '数据库',
      usernameLabel: '用户名',
      tlsLabel: 'TLS',
      tlsModes: {
        disabled: 'DISABLED',
        serverAuthenticated: 'SERVER_AUTHENTICATED',
        mutual: 'MUTUAL',
      },
      secretLabel: '凭据版本',
      secretHelper: '保存后生成第一个凭据版本。凭据版本不可修改，维护凭据是新建一个版本。',
      submit: '登记',
      /** Not 「取消」: that word is a migration run's terminal stop (`CONTEXT.md`). */
      cancel: '关闭',
    },
    addCredentialVersion: {
      title: '新建凭据版本',
      description: '历史凭据版本保持不可变；新建版本后需要重新校验数据库连接。',
      usernameLabel: '用户名',
      secretLabel: '凭据版本',
      submit: '新建',
      cancel: '关闭',
    },
  },
  settings: {
    title: '系统设置',
    lead: '本期仅保留导航位置，暂无内容。',
  },
  densitySample: {
    title: '中文密度样例',
    lead: '用于人工确认：32px 行高下的中文正文是否清晰可读。左右两档分别为密集（32px）与舒适（40px）。',
    condensedHeading: '密集 32px',
    comfortableHeading: '舒适 40px',
    controlsHeading: '表单控件',
    tableNameLabel: '源表名称',
    tableNameHelper: '标签与辅助文字为 13px，字距归零。',
    columns: {
      sourceTable: '源表',
      targetTable: '目标表',
      rowCount: '源基线行数',
      phase: '阶段',
      conclusion: '预检结论',
    },
    phases: {
      readComplete: '读取完成',
      writeComplete: '写入完成',
      migrationComplete: '迁移完成',
      stuck: '卡死',
    },
    // Preflight conclusions have no `_中文_` line in CONTEXT.md, so DBX shows the enum
    // literal rather than inventing a translation. #30 writes them this way too
    // ("预检结论是 `SUPPORTED`、`UNSUPPORTED` 还是 `INCONCLUSIVE`"). If a Chinese wording
    // is ever wanted, it is added to CONTEXT.md first.
    conclusions: {
      supported: 'SUPPORTED',
      unsupported: 'UNSUPPORTED',
      inconclusive: 'INCONCLUSIVE',
    },
  },
  common: {
    retry: '重试',
  },
  placeholder: {
    notYetBuilt: '本页面将在后续批次交付。',
  },
} as const;

export type Messages = typeof messages;
