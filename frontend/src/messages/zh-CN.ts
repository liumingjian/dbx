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
    databaseConnections: '数据源',
    settings: '系统设置',
  },
  domain: {
    migrationTask: '迁移任务',
    migrationDraft: '迁移草稿',
    migrationRun: '迁移运行',
    tableMigrationUnit: '表迁移单元',
    databaseConnection: '数据库连接',
    credentialVersion: '凭据版本',
    preflight: '预检',
    tableWriteContract: '表写入契约',
    structuralProof: '结构证明',
    validationExecution: '校验执行',
    validationDisposition: '校验处置',
    writeFreeze: '写冻结',
    sourceBaseline: '源基线',
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
    conclusions: {
      supported: '受支持',
      unsupported: '不受支持',
      inconclusive: '无法判定',
    },
  },
  placeholder: {
    notYetBuilt: '本页面将在后续批次交付。',
  },
} as const;

export type Messages = typeof messages;
