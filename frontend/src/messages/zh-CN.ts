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
    listLabel: '迁移任务',
    /** A migration task is counted in 项; a source table is counted in 张 (see #34). */
    unitLabel: '项',
    columns: {
      name: '名称',
      databasePair: '数据库对',
      source: '源',
      target: '目标',
      latestRunStatus: '最近运行状态',
      selectedTableCount: '选定表数',
      runCount: '迁移运行次数',
      approvedAt: '批准时间',
    },
    /**
     * A migration run's status has no `_中文_` line in `CONTEXT.md`, so the interface
     * shows the enum literal rather than inventing a translation — the precedent batch 1
     * set for preflight conclusions. The indicator beside it comes from the conclusion
     * mapping module, so the meaning is never carried by colour alone.
     */
    runStatuses: {
      PREPARING: 'PREPARING',
      RUNNING: 'RUNNING',
      ATTENTION_REQUIRED: 'ATTENTION_REQUIRED',
      CANCELLING: 'CANCELLING',
      COMPLETED: 'COMPLETED',
      COMPLETED_WITH_FAILURES: 'COMPLETED_WITH_FAILURES',
      COMPLETED_WITH_ACCEPTED_RISK: 'COMPLETED_WITH_ACCEPTED_RISK',
      CANCELLED: 'CANCELLED',
    },
    neverRun: '尚未运行',
    filters: {
      heading: '筛选',
      status: '最近运行状态',
      databasePair: '数据库对',
      approvedWithin: '批准时间',
      any: '全部',
      lastSevenDays: '最近 7 天',
      lastThirtyDays: '最近 30 天',
      lastNinetyDays: '最近 90 天',
      clear: '清除筛选',
    },
    openRunsAction: '查看迁移运行',
    loading: '正在读取迁移任务。',
    empty: {
      title: '尚未批准任何迁移任务',
      body: '下一步：在数据源里登记源与目标的数据库连接，然后新建迁移草稿。迁移草稿经执行确认后才成为迁移任务。',
    },
    error: {
      title: '迁移任务读取失败',
      body: '这次请求没有成功。稍后重试，或确认 DBX 后端是否可达。',
    },
    runs: {
      title: '迁移运行',
      lead: '一次迁移运行是对该迁移任务的一次不可变执行尝试；重新迁移是新的迁移运行，不是原地重试。',
      listLabel: '迁移运行',
      unitLabel: '次',
      columns: {
        id: '迁移运行',
        status: '状态',
        startedAt: '开始时间',
        endedAt: '结束时间',
        selectedTableCount: '选定表数',
        excludedTableCount: '排除表数',
      },
      inFlight: '进行中',
      openAction: '打开迁移运行',
      backAction: '返回迁移任务',
      loading: '正在读取迁移运行。',
      empty: {
        title: '该迁移任务还没有迁移运行',
        body: '迁移任务在执行确认后生成首个迁移运行。',
      },
      error: {
        title: '迁移运行读取失败',
        body: '这次请求没有成功。稍后重试，或确认 DBX 后端是否可达。',
      },
    },
  },
  /**
   * 迁移草稿 — an unapproved, discardable working set that has not become a migration task.
   *
   * The wording keeps the two apart on purpose: `Migration task` lists 「unapproved
   * migration task」 under `_Avoid_`, so a draft is never described as a task in any state.
   */
  drafts: {
    title: '迁移草稿',
    lead: '迁移草稿尚未批准：它不产生迁移运行，也不会被审计引用，丢弃后不留痕迹。经执行确认后才成为迁移任务。',
    listLabel: '迁移草稿',
    /** A draft is counted in 份; a migration task in 项; a source table in 张. */
    unitLabel: '份',
    columns: {
      name: '名称',
      sourceDatabase: '源 MySQL database',
      targetSchema: '目标 PostgreSQL schema',
      selectedTableCount: '已选表数',
      updatedAt: '最近修改',
      actions: '操作',
    },
    unnamed: '未命名迁移草稿',
    notChosen: '尚未选择',
    createAction: '新建迁移草稿',
    continueAction: '继续编辑',
    discardAction: '丢弃',
    discard: {
      title: '丢弃这份迁移草稿？',
      body: '迁移草稿丢弃后不留痕迹：它没有迁移运行，也不会留在审计记录里。已批准的迁移任务不受影响。',
      confirm: '丢弃',
      cancel: '关闭',
    },
    loading: '正在读取迁移草稿。',
    empty: {
      title: '当前没有迁移草稿',
      body: '下一步：新建迁移草稿，选择源与目标的数据库连接，再挑选迁移范围。',
    },
    error: {
      title: '迁移草稿读取失败',
      body: '这次请求没有成功。稍后重试，或确认 DBX 后端是否可达。',
    },
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
    /** One progress indicator only (ADR-0007): no second vertical stage rail. */
    progressLabel: '迁移向导阶段',
    backAction: '上一步',
    nextAction: '下一步',
    discardAction: '丢弃迁移草稿',
    exitAction: '返回迁移任务',
    blockedTitle: '还不能进入下一阶段',
    loading: '正在读取迁移草稿。',
    notFound: {
      title: '找不到这份迁移草稿',
      body: '它可能已经被丢弃。迁移草稿丢弃后不留痕迹，因此没有可恢复的内容。',
    },
    error: {
      title: '迁移草稿读取失败',
      body: '这次请求没有成功。稍后重试，或确认 DBX 后端是否可达。',
    },
    notYetBuilt: '本阶段将在后续批次交付。',
    /**
     * Why a stage will not let the operator move on. Each line belongs to exactly one
     * stage's gate (`src/wizard/stageGates.ts`); the stage that owns the rule owns the
     * sentence, so a gate can never be enforced in one place and explained in another.
     */
    gates: {
      connectionsIncomplete:
        '请先选择源与目标的数据库连接，并指定源 MySQL database 与目标 PostgreSQL schema。',
      connectionUnusable: (name: string, outcome: string) =>
        `数据库连接「${name}」的最近校验是 ${outcome}，不能用于迁移。请在数据源里重新校验，或改选其他数据库连接。`,
      /** Gate 1 (#30 §15.4): 一张表都没选时不能前进. */
      noTableSelected: '请先选择至少一张表纳入迁移范围；空的迁移范围不会被创建。',
      stageNotYetDelivered: '本阶段将在后续批次交付，暂时无法继续。',
      stageBelongsToRun: '本阶段属于迁移运行，执行确认之后才会出现。',
    },
    connections: {
      lead: '数据库连接与凭据版本只在数据源里维护；向导从已登记的数据库连接中选择，不录入凭据。',
      sourceHeading: '源端',
      targetHeading: '目标端',
      connectionLabel: '数据库连接',
      sourceDatabaseLabel: '源 MySQL database',
      targetSchemaLabel: '目标 PostgreSQL schema',
      targetSchemaHelper: '目标 schema 是本次迁移的落点。凭据不在这里录入。',
      chooseConnection: '请选择数据库连接',
      chooseDatabase: '请选择数据库',
      endpointLabel: '端点',
      latestCheckLabel: '最近校验',
      neverChecked: '尚未校验',
      manageConnectionsLink: '前往数据源',
      unusableTitle: '这个数据库连接现在不可用',
      /** Worded apart from the gate's sentence so the two never print the same line twice. */
      unusableDetail: (outcome: string) =>
        `最近校验是 ${outcome}。请在数据源里重新校验，或改选其他数据库连接。`,
      resolvedPairLabel: '本次迁移落点',
    },
    scope: {
      lead: '选中的表进入逐表配置与预检；显式排除的表不迁移，也不会被计为校验失败。',
      listLabel: '源表',
      /** A source table is counted in 张. */
      unitLabel: '张',
      searchLabel: '按名称搜索源表',
      searchPlaceholder: '表名',
      columns: {
        name: '表名',
        sourceDatabase: '源数据库',
        columnCount: '列数',
        estimatedRowCount: '预估行数',
        estimatedBytes: '预估数据量',
        condition: '当前情况',
      },
      /** `Source baseline` lists 「estimated row count」 under `_Avoid_`: these are not one. */
      estimateNotice: '行数与数据量是发现阶段的预估值，不是源基线；源基线在写冻结之后捕获。',
      largeRecordTable: '大记录表',
      mappingExceptions: (count: number) => `映射规则 ${count} 条`,
      blockingFindings: (count: number) => `阻断发现 ${count} 项`,
      summaryLabel: '迁移范围汇总',
      /**
       * The draft-level total, worded differently from `DbxTable`'s 「已选 N 张」 on
       * purpose: the table's count is about the rows matching the current search, and this
       * one is about what the 迁移草稿 will carry into the next stage. Two counts that
       * differ must not share a sentence.
       */
      scopeTotal: (count: number) => `迁移范围共 ${count} 张`,
      excludedHeading: '显式排除',
      excludedEmpty: '还没有显式排除任何表。',
      excludedConsequence: '显式排除是可复核的例外：这些表不迁移，也不会被计为校验失败。',
      restoreAction: (name: string) => `撤销排除 ${name}`,
      loading: '正在读取源表。',
      empty: {
        title: '这个源数据库里没有表',
        body: '返回上一阶段换一个源 MySQL database 再看。',
      },
      error: {
        title: '源表读取失败',
        body: '这次请求没有成功。稍后重试，或确认 DBX 后端是否可达。',
      },
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
  },
  /**
   * The migration boundaries of `CONTEXT.md`, as product vocabulary.
   *
   * These words used to live under `densitySample`, which made the design reference page
   * the owner of the product's status language. They are the product's (#33, lead
   * decision D6); `/design/density` now reads them from here like any other view.
   */
  phase: {
    readComplete: '读取完成',
    writeComplete: '写入完成',
    migrationComplete: '迁移完成',
    stuck: '卡死',
  },
  conclusion: {
    /**
     * Labels for the closed set of judgements DBX renders as an indicator.
     *
     * Only 卡死 has a `_中文_` line in `CONTEXT.md`. The rest are enum literals, because
     * `CONTEXT.md` carries no Chinese wording for them and the repository rule is to add
     * the word there first rather than invent one here. `IN_FLIGHT` is the gap this
     * ticket adds to that list.
     */
    labels: {
      SUPPORTED: 'SUPPORTED',
      UNSUPPORTED: 'UNSUPPORTED',
      INCONCLUSIVE: 'INCONCLUSIVE',
      PASS: 'PASS',
      FAIL: 'FAIL',
      NOT_APPLICABLE: 'NOT_APPLICABLE',
      NOT_RUN: 'NOT_RUN',
      IN_FLIGHT: 'IN_FLIGHT',
      STUCK: '卡死',
    },
  },
  /**
   * Copy owned by the `DbxTable` boundary (ADR-0015). Cross-page selection wording, the
   * density switcher and the column-visibility control are DBX's own — Carbon publishes
   * none of them — so their words live here rather than being taken from the substrate.
   */
  table: {
    unitLabel: '项',
    density: {
      label: '行高',
      /** ADR-0014: 32px is the smallest usable row height in Chinese; `xs` (24px) is unavailable. */
      condensed: '密集（32px）',
      comfortable: '舒适（40px）',
    },
    columnsAction: '列显示',
    columnsTitle: '列显示',
    columnsDescription: '取消勾选可以隐藏该列。标识列始终显示。',
    columnsClose: '关闭',
    selection: {
      columnLabel: '选择',
      rowLabel: (row: string) => `选择 ${row}`,
      selectedCount: (count: number, unit: string) => `已选 ${count} ${unit}`,
      allMatchingSelected: (count: number, unit: string) =>
        `已选中符合当前筛选的全部 ${count} ${unit}`,
      excludedCount: (count: number, unit: string) => `已排除 ${count} ${unit}`,
      selectPageAction: '当前页全选',
      clearPageAction: '取消当前页选择',
      selectAllMatchingAction: '选中符合当前筛选的全部',
      clearAction: '清除选择',
      undoAction: '撤销',
      excludeAction: '排除',
    },
    batch: {
      label: '批量动作',
      confirmAction: '确认',
      cancelAction: '关闭',
      undoneNotice: '已撤销上一步动作。',
    },
    /** `_Avoid_`: 「没有数据」 when a filter is what emptied the table. */
    noMatches: {
      title: '没有匹配项',
      body: '当前筛选条件没有匹配到任何行。放宽或清除筛选后再看。',
    },
    pagination: {
      itemsPerPage: '每页行数',
      backward: '上一页',
      forward: '下一页',
      itemRange: (min: number, max: number, total: number) => `第 ${min}–${max} 行，共 ${total} 行`,
      pageRange: (current: number, total: number) => `第 ${current} 页，共 ${total} 页`,
    },
    loading: '正在读取。',
  },
  common: {
    retry: '重试',
  },
  placeholder: {
    notYetBuilt: '本页面将在后续批次交付。',
  },
} as const;

export type Messages = typeof messages;
