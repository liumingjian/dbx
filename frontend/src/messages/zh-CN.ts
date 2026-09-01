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
      /** Stage three, #35's clause: a table with an undecided mapping exception has no contract. */
      tableConfigurationsUnread: '还没有读到逐表配置与预检，暂时无法判断能否进入下一阶段。',
      contractNotGenerated: (count: number, example: string) =>
        `迁移范围内还有 ${count} 张表没有生成表写入契约（例如 ${example}）。请在逐表配置里决定它们的映射例外。`,
      /**
       * Gate 2 (#30 §15.4): 预检结论为 UNSUPPORTED 或 INCONCLUSIVE 的表不能被批准.
       *
       * `CONTEXT.md` on 预检: 「only `SUPPORTED` may proceed」, and its `_Avoid_` line names
       * 「warning acknowledgement」 — so the sentence names the three exits rather than
       * offering a way past. The conclusion is rendered as the enum literal, as everywhere
       * else in DBX (lead decision D13).
       */
      preflightInFlight: (count: number, example: string) =>
        `迁移范围内还有 ${count} 张表的预检正在进行（例如 ${example}）。预检得出结论前不能进入下一阶段。`,
      preflightNotSupported: (count: number, example: string, conclusion: string) =>
        `迁移范围内有 ${count} 张表的预检结论不是 SUPPORTED（例如 ${example}：${conclusion}）。只有 SUPPORTED 的预检可以继续。请修正源后重新预检、裁剪超限字段后重跑预检，或显式排除该表。`,
      preflightBlockingFindings: (count: number, example: string) =>
        `迁移范围内有 ${count} 张表带着阻断发现（例如 ${example}）。阻断发现不能被确认掉，只能被解决或让该表退出迁移范围。`,
      /**
       * 执行确认 (#37). The stage's gate is the pair of constraints the whole stage exists
       * for, evaluated in the order a safety sequence reads them: what is not yet known,
       * then 写冻结, then 结构证明, and only then the fact that leaving this stage is not
       * something 「下一步」 does at all.
       */
      executionSummaryUnread: '还没有读到执行确认汇总，暂时无法判断能否启动迁移。',
      /**
       * **Gate 5**: 「没有写冻结确认就无法启动」.
       *
       * `CONTEXT.md` lists 「permanent checkbox」 under 写冻结's `_Avoid_`, so the sentence
       * names what a confirmation actually consists of — a 责任人 and a 时限 — rather than
       * asking for a tick.
       */
      writeFreezeNotConfirmed:
        '启动前必须确认源端写冻结，并写明责任人与时限。没有写冻结确认，DBX 不会创建迁移运行。',
      /**
       * **Gate 6**: 「没有结构证明就不会开始写入目标」.
       *
       * The frontend cannot enforce this — 结构证明 is a server-side catalog comparison
       * performed inside the 迁移运行 — so what it does is state the constraint and refuse
       * to start while the summary reports a table it cannot be established for (lead
       * decision D11).
       */
      structuralProofMissing: (count: number, example: string) =>
        `还有 ${count} 张表无法建立结构证明（例如 ${example}）。没有结构证明，DBX 不会开始写入目标表。`,
      /**
       * Nothing is wrong, and the stage still does not lead anywhere: 运行监控 belongs to a
       * 迁移运行, and a 迁移运行 only exists once the operator starts one deliberately.
       */
      runNotStarted: '执行确认不通往下一步：按「开始迁移」创建迁移运行之后，运行监控才会出现。',
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
    /**
     * 阶段三 逐表配置与预检 — the single-table workspace (#35).
     *
     * Two words carry the whole stage and both come from `CONTEXT.md`: 表写入契约 is the
     * immutable write intent, and 映射规则 is 「a structured, reviewable exception to
     * DBX's automatic table or column mapping … never arbitrary SQL or regular
     * expressions」. The copy below never calls the DDL a script, an editor or a
     * configuration, because ADR-0011 lists 「editable DDL」 among its rejected
     * alternatives — the DDL is one rendering of the contract and nothing else.
     */
    tables: {
      lead: '字段映射默认自动完成。这里集中列出需要复核的结构化映射例外，改动映射后表写入契约与 DDL 会重新生成。',
      treeLabel: '对象树',
      treeSearchLabel: '按名称搜索迁移范围内的表',
      treeSearchPlaceholder: '表名',
      treeTruncated: (shown: number, total: number) =>
        `对象树显示 ${shown} 张，迁移范围共 ${total} 张。按名称搜索缩小范围。`,
      /** The object kinds the tree groups by. All five are structures MySQL reports. */
      objectKinds: {
        COLUMN: '列',
        PRIMARY_KEY: '主键',
        UNIQUE_CONSTRAINT: '唯一约束',
        INDEX: '索引',
        FOREIGN_KEY: '外键',
      },
      /**
       * ADR-0011 keeps unique constraints other than the primary key, ordinary indexes,
       * foreign keys, comments and collation outside the v1 writable-table boundary. They
       * are preserved as 补建 SQL — `Supplemental SQL`'s `_中文_` — and 「DBX v1 delivers
       * it but does not execute it as part of migration」.
       */
      outOfContract: '补建 SQL',
      outOfContractNotice:
        '标为补建 SQL 的对象不在 v1 可写表边界内：DBX 交付脚本，但不在迁移过程中执行。',
      largeRecordTable: '大记录表',
      prunedColumn: '已裁剪',
      prunedColumnCount: (count: number) => `已裁剪 ${count} 个字段`,
      chooseTable: '在对象树里选择一张表，查看它的源 DDL、目标 DDL 与发现。',
      sourceDdlTitle: '源 DDL（MySQL 8.0）',
      targetDdlTitle: '目标 DDL（PostgreSQL 15）',
      supplementalTitle: '补建 SQL',
      /** Story 44: the operator must not mistake this for a SQL editor. */
      readOnlyNotice:
        'DDL 是表写入契约的只读完整呈现，不是可以手改的 SQL 编辑器。要改变结构，请在发现列表里改映射规则，契约与 DDL 会重新生成。',
      copyAction: (what: string) => `复制${what}`,
      copied: '已复制',
      contractVersion: (version: number) => `表写入契约 v${version}`,
      contractGeneratedAt: (moment: string) => `重新生成于 ${moment}`,
      contractMissing: {
        title: '尚未生成表写入契约',
        body: '还有映射例外没有决定。表写入契约是完整的写入意图，缺一项就不生成，DDL 也不会呈现半份。',
      },
      findingsLabel: '发现',
      /**
       * 预检 (#36) — the place the product's judgement is most concentrated.
       *
       * Two rules govern every string below. `CONTEXT.md` lists 「warning acknowledgement」
       * under 预检's `_Avoid_`, so nothing here offers to acknowledge, confirm or ignore a
       * conclusion — the only words on offer are the three exits. And `INCONCLUSIVE` is
       * never called a warning: ADR-0003 gives it its own sentence, 「无法确认是否可迁移」,
       * which is a different statement from 「无法迁移」 and from any caution.
       *
       * The two quoted sentences are ADR-0003's own, character for character. It fixes the
       * exact wording of the over-limit and inconclusive reviews, so they are copied rather
       * than paraphrased.
       */
      preflight: {
        label: '预检',
        conclusionLabel: '预检结论',
        evaluatedAt: (moment: string) => `评估于 ${moment}`,
        /** Story 48: a scan that takes time must not be read as a frozen interface. */
        inFlight: '预检进行中：DBX 正在对选定列做精确扫描，界面没有卡死。',
        inFlightNotice: '重跑期间不显示上一次的结论——过期的结论比没有结论更危险。',
        findingsLabel: '预检发现',
        noFindings: '本表的预检没有发现。',
        blocking: '阻断',
        nonBlocking: '不阻断',
        /** ADR-0003: 大记录表 is a fact about bytes, and the interface states the bytes. */
        largeRecordTable: '大记录表',
        largestValue: (bytes: string) => `最大单值 ${bytes} 字节`,
        largestRow: (bytes: string) => `最大行 ${bytes} 字节`,
        envelope: '大记录包络上限为 20 MiB（20,971,520 字节）；超过 1 MiB 即为大记录表。',
        /**
         * A finding is rendered as its stable code plus a sentence. `CONTEXT.md` carries no
         * `_中文_` for these codes, so the literal is shown (lead decision D13) and the
         * sentence explains it, exactly as 映射例外 already does.
         */
        codes: {
          LARGE_RECORD_VALUE: '单个源值的精确字节数。超过 20 MiB 上限即无法迁移。',
          LARGE_RECORD_ROW: '整行序列化前载荷的精确字节数。裁掉一个字段不豁免整行检查。',
          VALUE_DOMAIN_OUT_OF_RANGE: '源值域超出表写入契约将写入的目标类型可以承载的范围。',
          ZERO_DATE_VALUE_REJECTED: '按当前映射规则保持 NOT NULL，源端的零日期值在目标端会被拒绝。',
          ENVELOPE_SCAN_INCONCLUSIVE: 'DBX 未能完成 20 MiB 精确预检，因此无法判定这张表。',
        },
        /** ADR-0003 fixes this sentence for a value above the 大记录包络. */
        overEnvelopeTitle: (table: string, coordinate: string, bytes: string) =>
          `无法迁移：表 ${table} 的 ${coordinate} 最大为 ${bytes} 字节，超过 DBX v1 的 20 MiB（20,971,520 字节）上限。请选择排除此表、裁剪超限字段后重新预检，或中止并在源端缩减数据；不能忽略此限制继续迁移。`,
        /** ADR-0003 fixes this sentence for a scan that could not conclude. */
        inconclusiveTitle: (reason: string) =>
          `无法确认是否可迁移：DBX 未能完成 20 MiB 精确预检（${reason}）。修复权限、超时或连接问题后重试；不能忽略此检查继续迁移。`,
        /** The same shape, for a block that is not about the envelope. */
        unsupportedTitle: (table: string) =>
          `无法迁移：表 ${table} 的预检结论是 UNSUPPORTED。请按下面三条出路之一处理；不能忽略此结论继续迁移。`,
        exits: {
          heading: '面对阻断，有三条出路',
          /** The whole point of the panel: there is no fourth exit, and no acknowledgement. */
          noFourth: '没有第四条出路：预检结论不能被确认掉，被阻断的状态也不能被关闭。',
          fixSource: {
            title: '修正源',
            body: '在源端缩减数据、修复权限或超时之后重新预检。DBX 不改源端数据，也不会替你放宽结论。',
            action: '重新预检',
          },
          pruneColumn: {
            title: '裁剪超限字段后重跑预检',
            body: '把阻断发现指名的字段裁出本表的选定列，DBX 按裁剪后的选定列重跑预检。裁掉一个字段不豁免整行检查。',
            action: (column: string) => `裁剪字段 ${column}`,
            restoreAction: (column: string) => `撤销裁剪 ${column}`,
            prunedHeading: '已裁剪字段',
            unavailable: '本表的阻断发现没有指名字段，裁剪解决不了它。',
          },
          excludeTable: {
            title: '显式排除该表',
            body: '显式排除是可复核的例外：这张表不迁移，也不会被计为校验失败。',
            action: '显式排除该表',
          },
        },
      },
      mappingListLabel: '映射例外',
      /** A mapping rule is counted in 条, as 迁移范围 already counts them. */
      mappingUnitLabel: '条',
      mappingColumns: {
        sourceColumn: '源字段',
        sourceType: '源类型',
        reason: '例外原因',
        rule: '映射规则',
        origin: '规则来源',
      },
      mappingReasons: {
        ENUM_VALUE_DOMAIN: 'MySQL enum 在目标端没有对应类型，值域需要由字符串类型承载。',
        UNSIGNED_INTEGER_RANGE: '无符号整数的取值范围超出同名有符号目标类型。',
        AUTO_INCREMENT_IDENTITY: 'numeric(20,0) 自增列在目标端由标识列或独占序列承载。',
        ZERO_DATE_DEFAULT:
          '零日期默认值不在受支持的默认值白名单内；逐列放宽必须由人批准，DBX 不会替你决定。',
      },
      mappingConsequences: {
        PRESERVES_VALUE_DOMAIN: '保留原值域，不截断。',
        FIXED_WIDTH_MAY_TRUNCATE: '固定宽度，超长的值会被拒绝。',
        WIDER_TARGET_TYPE: '用更宽的目标类型容纳全部取值。',
        EXACT_NUMERIC_TARGET: '用精确数值类型容纳全部取值。',
        PLATFORM_OWNED_IDENTITY: '由目标端标识列生成。',
        OWNED_EXPLICIT_SEQUENCE: '由该表独占的显式序列生成。',
        REJECTS_ZERO_DATE: '保持 NOT NULL；零日期值在预检阶段被判为阻断。',
        APPROVED_ZERO_DATE_RELAXATION: '按已批准的逐列零日期放宽，允许为空。',
      },
      /**
       * `CONTEXT.md` carries no `_中文_` for a rule's origin, so the interface shows the
       * enum literal — the precedent batch 1 set for preflight conclusions (lead decision
       * D13). 「user rules override automatic rules」 is what the column is for.
       */
      ruleOrigins: { PLATFORM: 'PLATFORM', USER: 'USER' },
      chooseRule: '请选择',
      ruleLabel: (column: string) => `为 ${column} 选择映射规则`,
      undecided: (count: number) => `还有 ${count} 项映射例外需要你决定；DBX 不会替你选。`,
      noExceptions: {
        title: '字段均使用自动映射',
        body: '本表没有需要处理的结构化映射例外，不必逐字段自检。',
      },
      emptyScope: {
        title: '迁移范围里没有表',
        body: '返回上一阶段，至少选择一张表纳入迁移范围。',
      },
      loading: '正在读取逐表配置与预检。',
      error: {
        title: '逐表配置读取失败',
        body: '这次请求没有成功。稍后重试，或确认 DBX 后端是否可达。',
      },
    },
    /**
     * 阶段四 执行确认 — the last screen before a production migration starts (#37).
     *
     * Three words from `CONTEXT.md` carry it. 迁移运行 is 「one **immutable** execution
     * attempt」, so the copy says the scope cannot be altered afterwards rather than
     * implying a draft that can be revised. 写冻结 is 「externally enforced, time-bounded
     * … with an accountable operator and expiry」 and lists 「permanent checkbox」 under
     * `_Avoid_`, so nothing here is a tick and nothing here is open-ended. 结构证明 is 「the
     * deterministic comparison of the actual PostgreSQL table … only zero difference
     * permits the Sink to start」, and because that comparison happens server-side inside
     * the run, the interface states the constraint rather than claiming to perform it.
     */
    confirm: {
      lead: '启动前的最后一次全局核对：源、目标、选中的表、显式排除、将要批准的表写入契约，以及仍未解决的发现。',
      scopeHeading: '本次执行范围',
      sourceLabel: '源',
      targetLabel: '目标',
      /** How the operator stated the 迁移范围; the two are different decisions (D20). */
      scopeKinds: {
        SELECTED_TABLES: '逐张勾选',
        ALL_TABLES_EXCEPT: '符合当前筛选的全部，减去显式排除的',
      },
      tablesHeading: '选中的表',
      /** A source table is counted in 张, a 表写入契约 in 份, a column in 列. */
      unitLabel: '张',
      tableColumns: {
        sourceTable: '源表',
        targetTable: '目标表',
        preflightConclusion: '预检结论',
        contract: '表写入契约',
        columnCount: '写入列数',
        condition: '当前情况',
      },
      contractVersion: (version: number) => `v${version}`,
      contractMissing: '尚未生成',
      contractsSummary: (tables: string, columns: string) =>
        `本次将批准 ${tables} 份表写入契约，共 ${columns} 列。表写入契约是不可变的单表写入意图，启动后不再改动。`,
      excludedHeading: '显式排除',
      excludedEmpty: '没有显式排除任何表。',
      excludedConsequence: '显式排除是可复核的例外：这些表不迁移，也不会被计为校验失败。',
      /**
       * The part of the summary that must not be skimmed past.
       *
       * A blocking 发现 cannot reach this stage — stage three's gate holds it — so
       * everything listed here was found, judged non-blocking, and never resolved. It
       * travels into the migration with the table it belongs to, which is why it is stated
       * at the top of the page instead of at the bottom.
       */
      findingsHeading: '未解决的发现',
      findingsNotice: (count: string) =>
        `迁移范围里还有 ${count} 项未解决的发现。它们不阻断启动，但会随这次迁移一起被带走；启动前请逐条看过。`,
      findingsEmpty: '迁移范围内没有未解决的发现。',
      findingColumns: { sourceTable: '源表', code: '发现', coordinate: '源坐标', detail: '证据' },
      wholeTable: '整表',
      /** 写冻结 — `CONTEXT.md` 的 `_Avoid_` 明确排除「permanent checkbox」. */
      freezeHeading: '写冻结',
      freezeConstraint:
        '写冻结由源端在 DBX 之外保障，DBX 只记录这份承诺：它必须从源基线捕获开始一直有效，直到每张选中的表进入校验终态或执行停止。',
      operatorLabel: '责任人',
      operatorHelper: '写明为这次写冻结负责的人。责任人不能留空。',
      durationLabel: '时限',
      durationOption: (hours: number) => `${hours} 小时`,
      changeReferenceLabel: '变更单号',
      changeReferenceHelper: '选填：写冻结是在哪张变更单下安排的。',
      expiryPreview: (moment: string) => `若现在启动，写冻结到期时间为 ${moment}。`,
      confirmFreezeAction: '确认写冻结',
      revokeFreezeAction: '撤销写冻结确认',
      freezeConfirmed: (operator: string, hours: number) =>
        `${operator} 已确认写冻结，时限 ${hours} 小时。`,
      freezeNotConfirmed: '尚未确认写冻结。',
      /** 结构证明 — stated, never simulated. See lead decision D11. */
      proofHeading: '结构证明',
      proofConstraint:
        '结构证明是目标表与已批准的表写入契约的确定性比对，只有零差异才允许开始写入。没有结构证明，DBX 不会向目标表写入任何数据。结构证明由平台在迁移运行内、建表之后完成，因此这一步只声明约束，不代为完成。',
      proofReady: (count: string) => `${count} 张表将在写入前逐张完成结构证明。`,
      proofGapsHeading: '无法建立结构证明的表',
      proofGaps: {
        CONTRACT_NOT_APPROVED: '没有已批准的表写入契约，结构证明没有可比对的对象。',
        TARGET_TABLE_EXISTS:
          '目标 schema 里已经存在同名表。首次运行遇到已存在的目标表会退回复核，DBX 不会复用、清空或替换它。',
      },
      startAction: '开始迁移',
      /**
       * Starting is an act requiring deliberate intent, never a stray click, so it asks
       * for the source database's own identifier — a thing only someone who knows what
       * they are starting can type.
       */
      start: {
        title: '确认开始迁移',
        body: '启动会把这份迁移草稿变成迁移任务，并生成第一次迁移运行。迁移运行是不可变的执行快照：此次执行的范围日后不可篡改，迁移草稿也不再存在。',
        challengeLabel: (database: string) => `输入源数据库名 ${database} 以确认`,
        challengeHelper: '这一步不能顺手点过：请手动输入源数据库名。',
        confirm: '确认并开始迁移',
        cancel: '取消',
      },
      startFailed: {
        title: '没有创建迁移运行',
        body: '这次启动没有成功，迁移草稿仍在原处。请核对写冻结与结构证明，然后再试一次。',
      },
      loading: '正在读取执行确认汇总。',
      error: {
        title: '执行确认汇总读取失败',
        body: '这次请求没有成功。稍后重试，或确认 DBX 后端是否可达。',
      },
    },
  },
  /**
   * 运行监控 (#38) — the wizard's fifth stage, seen from the 迁移运行 it observes.
   *
   * Two rules shape every string below.
   *
   * **Gate 7**: 箱、连接器、topic never reach the operator. `CONTEXT.md` states it as a
   * property of 箱 itself — 「Never. A box is an internal scheduling detail, and the
   * operator is not required to understand the execution platform」 — and gives the two
   * phase literals named after it their own entries: `WAITING_FOR_BOX` is 等待调度 and
   * `BLOCKED_BY_BOX_FAILURE` is 因关联失败而阻塞. Those two are used; the literals are not
   * rendered. Every other phase and outcome has no `_中文_` line, so the interface shows
   * the enum literal rather than inventing a translation.
   *
   * **卡死 is a diagnosis, not a degree of slowness.** `CONTEXT.md` puts 「slow」 and
   * 「timed out」 under its `_Avoid_`, so the wording never grades it: it states the
   * threshold, the last observed movement, and what DBX did about it.
   */
  run: {
    title: '运行监控',
    lead: '监控以表迁移单元为中心：每张表的阶段、进度、技术结果与观测时间都是这张表自己的记录。',
    runLabel: '运行',
    unitLabel: '表迁移单元',
    loading: '正在读取迁移运行的进度观测。',
    error: {
      title: '迁移运行进度读取失败',
      body: '这次观测没有取到。稍后重试，或确认 DBX 后端是否可达。',
    },
    empty: {
      title: '这次迁移运行还没有表迁移单元',
      body: '迁移运行刚刚开始时还没有表迁移单元；进度观测到达后，这里会按表列出。',
    },
    notFound: {
      title: '找不到这次迁移运行',
      body: '这个迁移运行标识在当前数据里不存在。请从迁移任务的迁移运行列表重新进入。',
    },
    /**
     * ADR-0004 makes progress observations coalescable, so the interface says so in as
     * many words. It is not a disclaimer: a DBA who has learned to read a frozen bar as
     * 「卡住了」 needs to know that a frozen number here can simply mean 「还没有新的观测」.
     */
    observationNotice:
      '进度按观测渲染：观测可以被合并，因此进度可能跳变，也可能滞后。界面只显示已经观测到的数字和它的观测时间，不做平滑推进，也不做推算。',
    observedAt: (at: string) => `观测时间 ${at}`,
    lastProgressAt: (at: string) => `最近一次推进 ${at}`,
    lagging: '观测滞后',
    laggingDetail: (at: string) =>
      `这张表最近一次观测是 ${at}，早于本次快照。滞后是正常的，它不是卡死。`,
    noObservation: '尚无进度观测',
    freezeLabel: '写冻结',
    freezeSummary: (operator: string, expires: string) => `责任人 ${operator}，时限至 ${expires}`,
    startedAt: '开始时间',
    endedAt: '结束时间',
    stillRunning: '进行中',
    sourceLabel: '源',
    targetLabel: '目标',
    statusLabel: '迁移运行状态',
    backAction: '返回迁移运行列表',
    matrix: {
      heading: '进度矩阵',
      totals: (read: string, written: string, baseline: string) =>
        `已观测：读取 ${read} 行、写入 ${written} 行；源基线共 ${baseline} 行`,
      phaseHeading: '阶段与技术结果分布',
      countOf: (label: string, count: number) => `${label} ${count}`,
      /** Bounded rendering states its bound and the true total (lead decision D24). */
      bounded: (shown: number, total: number) =>
        `仅显示 ${shown} 个表迁移单元，这次迁移运行共 ${total} 个。`,
      columns: {
        sourceTable: '源表',
        targetTable: '目标表',
        phase: '阶段',
        progress: '进度',
        outcome: '技术结果',
        observedAt: '观测时间',
      },
      progressOf: (read: string, baseline: string) => `${read} / ${baseline} 行`,
      /** Never a smooth bar: the number and its observation time are what is真实的. */
      readLabel: '已读',
      writtenLabel: '已写',
      noOutcome: '尚无技术结果',
    },
    filters: {
      heading: '筛选表迁移单元',
      all: '全部',
      failed: '只看失败',
      stuck: '只看卡死',
      noMatch: {
        title: '没有匹配项',
        body: '当前筛选下没有表迁移单元。切换回「全部」可以看到这次迁移运行的所有表。',
      },
    },
    /**
     * Phases (ADR-0004). `WAITING_FOR_BOX` is 等待调度 — an ordinary waiting state, not a
     * fault, carrying no diagnosis. The rest have no `_中文_` line and are shown as the
     * enum literal, following the precedent batch 1 set for preflight conclusions.
     */
    phases: {
      DISCOVERED: 'DISCOVERED',
      PREFLIGHTING: 'PREFLIGHTING',
      AWAITING_APPROVAL: 'AWAITING_APPROVAL',
      READY: 'READY',
      CREATING_TARGET: 'CREATING_TARGET',
      WAITING_FOR_BOX: '等待调度',
      TRANSFERRING: 'TRANSFERRING',
      VALIDATING: 'VALIDATING',
      TERMINAL: 'TERMINAL',
    },
    /**
     * Outcomes (ADR-0004). `BLOCKED_BY_BOX_FAILURE` is 因关联失败而阻塞: the unit's own
     * technical result is undetermined rather than failed, and it is a candidate for
     * re-migration. The others are enum literals for want of a `_中文_` line.
     */
    outcomes: {
      SUCCEEDED: 'SUCCEEDED',
      FAILED: 'FAILED',
      BLOCKED_BY_BOX_FAILURE: '因关联失败而阻塞',
      SKIPPED: 'SKIPPED',
      CANCELLED: 'CANCELLED',
      COMPLETED_WITH_ACCEPTED_RISK: 'COMPLETED_WITH_ACCEPTED_RISK',
    },
    /**
     * 根因域's `Kafka Connect` and `Kafka` are presented as the single 迁移平台 domain
     * (`CONTEXT.md`). The specific domain is kept in the diagnostic evidence for support,
     * so nothing is lost from the audit record — it is simply not the operator's business.
     */
    rootCauseDomains: {
      USER_INPUT: 'USER_INPUT',
      SOURCE_DATABASE: 'SOURCE_DATABASE',
      TARGET_DATABASE: 'TARGET_DATABASE',
      KAFKA_CONNECT: '迁移平台',
      KAFKA: '迁移平台',
      RUNTIME_ENVIRONMENT: 'RUNTIME_ENVIRONMENT',
      PLATFORM: 'PLATFORM',
    },
    /**
     * 单表证据 (#39) — one 表迁移单元's own 错误事件 and the 诊断 made of them.
     *
     * ADR-0005's three-way separation is what this copy has to keep intact, because it is
     * the difference between an interface a DBA can act on and one that merely looks
     * apologetic: an 错误事件 is an **observed fact**, a 诊断 is a **versioned
     * interpretation** of it, and the unit's 技术结果 is neither. So the sections are named
     * separately and the diagnosis states the catalog version it was reached under.
     *
     * The one question this drawer exists to answer is 「源问题还是目标问题」, and it is
     * answered by the 根因域 rather than by tone: 源数据库 and 目标数据库 are distinct
     * domains, an unidentified failure says outright that no cause was established, and
     * the execution platform's own domains are presented as 迁移平台 (Gate 7).
     */
    evidence: {
      title: '表迁移单元证据',
      lead: '错误事件是观测到的事实，诊断是对这些事实的解释。诊断不改变这张表的技术结果。',
      close: '关闭',
      loading: '正在读取这张表的证据。',
      error: {
        title: '表迁移单元证据读取失败',
        body: '这次读取没有取到。稍后重试，或确认 DBX 后端是否可达。',
      },
      notFound: {
        title: '找不到这个表迁移单元',
        body: '这个表迁移单元标识不在这次迁移运行里。请从进度矩阵重新进入。',
      },
      identityHeading: '这张表',
      observedAt: (at: string) => `证据观测时间 ${at}`,
      phaseLabel: '阶段',
      outcomeLabel: '技术结果',
      progressLabel: '进度',
      /**
       * 诊断 — 「a versioned interpretation of an error occurrence … An unknown or
       * conflicting diagnosis must not invent a cause」 (`CONTEXT.md`).
       */
      diagnosis: {
        heading: '诊断',
        none: {
          title: '这张表没有需要解释的失败',
          body: 'DBX 没有为这张表记录错误事件，因此不做诊断。没有结论比臆测的结论更有用。',
        },
        code: (code: string) => `诊断代码 ${code}`,
        catalogVersion: (version: string) => `诊断规则版本 ${version}`,
        rootCauseDomain: (domain: string) => `根因域 ${domain}`,
        sourceKind: (kind: string) => `诊断来源 ${kind}`,
        /**
         * The three source kinds have no `_中文_` line in `CONTEXT.md`, so they are shown
         * as the enum literal — the precedent batch 1 set for preflight conclusions
         * (lead decisions D13/D16/D23/D28/D31).
         */
        sourceKinds: {
          STRUCTURED: 'STRUCTURED',
          EXTERNAL_TRANSLATION: 'EXTERNAL_TRANSLATION',
          SYSTEM_FALLBACK: 'SYSTEM_FALLBACK',
        },
        affectedHeading: '影响',
        actionHeading: '建议动作',
        /**
         * The catalog, as the operator reads it. Each entry answers ADR-0005's four
         * questions in order: what happened, where it happened, what is affected, and the
         * one action recommended. Nothing here names the execution platform.
         */
        codes: {
          'DBX-SOURCE-PERMISSION-DENIED': {
            summary: '源数据库拒绝了这次读取：当前凭据版本没有读取这张表的权限。',
            affected: '这张表没有迁移完成；已经写入目标的数据与诊断证据都保留。',
            action: '在源数据库为该凭据授予这张表的读取权限，再用新的迁移运行重新迁移这张表。',
          },
          'DBX-SOURCE-VALUE-UNREPRESENTABLE': {
            summary: '源数据库里存在目标类型无法表示的值，写入因此终止。',
            affected: '这张表没有迁移完成；已经写入目标的数据与诊断证据都保留。',
            action:
              '在逐表配置与预检里为该列决定映射规则或裁剪该字段，重新预检后用新的迁移运行重新迁移。',
          },
          'DBX-TARGET-NOT-NULL-VIOLATION': {
            summary: '目标数据库拒绝了这次写入：一行数据要写入的目标列不接受空值。',
            affected: '这张表没有迁移完成；已经写入目标的数据与诊断证据都保留。',
            action:
              '在逐表配置与预检里为该列决定空值规则，重新生成表写入契约后用新的迁移运行重新迁移。',
          },
          'DBX-TARGET-VALUE-TOO-LONG': {
            summary: '目标数据库拒绝了这次写入：值超过目标列的长度上限。',
            affected: '这张表没有迁移完成；已经写入目标的数据与诊断证据都保留。',
            action:
              '在逐表配置与预检里为该列决定目标类型，重新生成表写入契约后用新的迁移运行重新迁移。',
          },
          'DBX-UNKNOWN': {
            summary: '没有可信的诊断规则匹配这次失败，DBX 不臆测原因。',
            affected: '这张表没有迁移完成；已经写入目标的数据与诊断证据都保留。',
            action: '保留目标数据与证据，导出诊断包并联系支持。',
          },
          'DBX-NO-OBSERVABLE-PROGRESS': {
            summary: '在配置的硬阈值内完全没有可观测进度，这次迁移运行被诊断为卡死。',
            affected: '这张表停在原地，技术结果未定：DBX 不会为了填满结果而给出责任判断。',
            action: '由人判断：可以取消这次迁移运行，也可以在排查后用新的迁移运行重新迁移这张表。',
          },
          'DBX-STOPPED-BY-RELATED-FAILURE': {
            summary: '这张表自己没有失败：它随一次关联失败一起停止。',
            affected: '技术结果未定，而不是失败，因此这张表是重新迁移的候选。',
            action: '排查关联失败之后，用新的迁移运行重新迁移这张表。',
          },
        },
      },
      /**
       * 错误事件 — 「an immutable fact that DBX observed at a phase and scope, retaining
       * the evidence and correlation needed to explain what happened」 (`CONTEXT.md`).
       */
      occurrences: {
        heading: '错误事件',
        lead: '观测到的事实，按指纹合并：同一种失败被观测多次时记录首次、最近与次数，而不是重复列出。',
        empty: '这张表没有错误事件。',
        observedPhase: (phase: string) => `观测阶段 ${phase}`,
        firstObservedAt: (at: string) => `首次观测 ${at}`,
        lastObservedAt: (at: string) => `最近观测 ${at}`,
        observationCount: (count: number) => `共观测 ${count} 次`,
        evidenceReference: (reference: string) => `证据引用 ${reference}`,
        detailHeading: '技术细节',
      },
      /**
       * 表写入契约 and 结构证明: the two facts that decide whether DBX was entitled to
       * write to the target at all. A DBA judging a target-side failure needs to know
       * that the structure had been proven — otherwise the failure would be about the
       * structure rather than about the data.
       */
      contract: {
        heading: '表写入契约与结构证明',
        version: (version: number) => `表写入契约版本 ${version}`,
        approvedAt: (at: string) => `批准时间 ${at}`,
        columnCount: (count: number) => `契约列 ${count} 列`,
        proven: (at: string) => `结构证明零差异，证明时间 ${at}`,
        notProven: '结构证明未通过：目标结构与已批准的表写入契约存在差异。',
        differencesHeading: '差异',
        none: '这张表还没有开始写入目标，因此还没有表写入契约与结构证明。',
      },
    },
    stuck: {
      heading: '卡死',
      /** 「slow」 and 「timed out」 are under 卡死's `_Avoid_`; the copy never grades it. */
      statement:
        '卡死是终局诊断，不是「慢」：在配置的硬阈值内完全没有可观测进度，而各项检查仍然报告健康。DBX 已经停止推进，并保留目标数据与诊断证据供排查。',
      notSlow:
        '与「慢」的区别：慢的表仍在推进，只是观测可能滞后；卡死的表在整个硬阈值内一行都没有推进。',
      diagnosedAt: (at: string) => `诊断时间 ${at}`,
      lastProgressAt: (at: string) => `最后一次可观测进度 ${at}`,
      threshold: (minutes: string) => `配置的硬阈值 ${minutes} 分钟`,
      noProgressFor: (minutes: string) => `已经 ${minutes} 分钟没有可观测进度`,
      rootCauseDomain: (domain: string) => `根因域 ${domain}`,
      stalledHeading: '停止推进的表',
      blockedHeading: '因关联失败而阻塞的表',
      blockedExplanation:
        '这些表自己没有失败：它们的技术结果未定，而不是失败，因此是重新迁移的候选。',
      nextStep:
        '下一步由人判断：可以取消这次迁移运行，也可以在排查后用新的迁移运行重新迁移受影响的表。',
    },
    events: {
      heading: '事件流',
      lead: '这次迁移运行的时间线，最近的在最前。',
      bounded: (shown: number, total: number) => `仅显示最近 ${shown} 条事件，共 ${total} 条。`,
      columns: { occurredAt: '时间', sourceTable: '表', event: '事件' },
      wholeRun: '整次迁移运行',
      types: {
        phaseEntered: (phase: string) => `进入阶段 ${phase}`,
        outcomeRecorded: (outcome: string) => `记录技术结果 ${outcome}`,
        runStatusChanged: (status: string) => `迁移运行状态 ${status}`,
        stuckDiagnosed: '诊断为卡死',
        cancellationRequested: '已请求取消',
      },
    },
    log: {
      heading: '日志',
      lead: '平台产生的技术证据，原样呈现，用于贴进工单。',
      bounded: (shown: number, total: number) => `仅显示最近 ${shown} 行，共 ${total} 行。`,
      empty: '这次迁移运行还没有产生日志。',
    },
    /**
     * 取消 — 「a user-requested terminal stop of a migration run that preserves … target
     * data and diagnostic evidence」, with 「discard」, 「delete」 and 「rollback」 under its
     * `_Avoid_`. The consequences are stated before the operator commits to them, and the
     * counts in them are read from the platform rather than guessed at in the browser.
     */
    cancel: {
      action: '取消迁移运行',
      heading: '取消迁移运行',
      lead: '取消之前，先看清它会做什么、不会做什么。',
      inFlight: (count: number) => `会停止 ${count} 个仍在进行中的表迁移单元。`,
      terminal: (count: number) =>
        `已经终局的 ${count} 个表迁移单元不受影响，它们的技术结果保持不变。`,
      preserved: '已经写入目标的数据与诊断证据都会保留：取消不是丢弃，也不回滚。',
      terminalStop:
        '取消是终局性的停止：这次迁移运行不会恢复，未完成的表需要新的迁移运行重新迁移。',
      confirmAction: '确认取消迁移运行',
      dismissAction: '返回',
      alreadyRequested: '这次迁移运行已经收到取消请求。',
      unavailable: '这次迁移运行已经结束，没有可以取消的东西。',
    },
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
