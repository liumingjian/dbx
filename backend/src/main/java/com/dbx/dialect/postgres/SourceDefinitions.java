package com.dbx.dialect.postgres;

import com.dbx.dialect.api.ColumnCoordinate;
import com.dbx.dialect.api.SourceForeignKey;
import com.dbx.dialect.api.SourceIndex;
import com.dbx.dialect.api.TableCoordinate;

/**
 * The source side's text for a deferred structure, which a comment in supplemental SQL quotes to the DBA
 * (ADR-0026 §Supplemental SQL). Source and target dialects quote under their own rules (ADR-0008 §Contract and
 * mapping boundary), so this dialect never renders a source name: the directed pair supplies the source
 * dialect's rendering. Text may hold a newline; the caller comments every line.
 */
public interface SourceDefinitions {

    /** The qualified source table. */
    String table(TableCoordinate table);

    /** The qualified source column. */
    String column(ColumnCoordinate column);

    /** The index as the source would create it. */
    String index(TableCoordinate table, SourceIndex index);

    /** The foreign key as the source would add it, without its referential rules. */
    String foreignKey(TableCoordinate table, SourceForeignKey foreignKey);
}
