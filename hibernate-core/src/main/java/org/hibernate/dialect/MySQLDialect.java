/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.dialect;

import java.sql.CallableStatement;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.hibernate.LockOptions;
import org.hibernate.PessimisticLockException;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.cfg.Environment;
import org.hibernate.dialect.function.CommonFunctionFactory;
import org.hibernate.dialect.hint.IndexQueryHintHandler;
import org.hibernate.dialect.identity.IdentityColumnSupport;
import org.hibernate.dialect.identity.MySQLIdentityColumnSupport;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.dialect.pagination.LimitLimitHandler;
import org.hibernate.dialect.sequence.NoSequenceSupport;
import org.hibernate.dialect.sequence.SequenceSupport;
import org.hibernate.dialect.unique.MySQLUniqueDelegate;
import org.hibernate.dialect.unique.UniqueDelegate;
import org.hibernate.engine.jdbc.Size;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.env.spi.IdentifierCaseStrategy;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelper;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelperBuilder;
import org.hibernate.engine.jdbc.env.spi.NameQualifierSupport;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.exception.LockAcquisitionException;
import org.hibernate.exception.LockTimeoutException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;
import org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtractor;
import org.hibernate.exception.spi.ViolatedConstraintNameExtractor;
import org.hibernate.internal.util.JdbcExceptionHelper;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.query.sqm.CastType;
import org.hibernate.query.sqm.IntervalType;
import org.hibernate.query.sqm.NullOrdering;
import org.hibernate.query.sqm.TemporalUnit;
import org.hibernate.query.spi.QueryEngine;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.query.sqm.mutation.internal.temptable.AfterUseAction;
import org.hibernate.dialect.temptable.TemporaryTable;
import org.hibernate.query.sqm.mutation.internal.temptable.BeforeUseAction;
import org.hibernate.query.sqm.mutation.internal.temptable.LocalTemporaryTableInsertStrategy;
import org.hibernate.query.sqm.mutation.internal.temptable.LocalTemporaryTableMutationStrategy;
import org.hibernate.dialect.temptable.TemporaryTableKind;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableInsertStrategy;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableMutationStrategy;
import org.hibernate.query.sqm.produce.function.FunctionParameterType;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.spi.StandardSqlAstTranslatorFactory;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.type.BasicTypeRegistry;
import org.hibernate.type.NullType;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.JsonJdbcType;
import org.hibernate.type.descriptor.jdbc.NullJdbcType;
import org.hibernate.type.descriptor.jdbc.spi.JdbcTypeRegistry;
import org.hibernate.type.descriptor.sql.internal.CapacityDependentDdlType;
import org.hibernate.type.descriptor.sql.internal.DdlTypeImpl;
import org.hibernate.type.descriptor.sql.spi.DdlTypeRegistry;

import jakarta.persistence.TemporalType;

import static org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtractor.extractUsingTemplate;
import static org.hibernate.type.SqlTypes.*;

/**
 * A {@linkplain Dialect SQL dialect} for MySQL 5.7 and above.
 *
 * @author Gavin King
 */
public class MySQLDialect extends Dialect {

	private static final DatabaseVersion MINIMUM_VERSION = DatabaseVersion.make( 5, 7 );

	private final UniqueDelegate uniqueDelegate = new MySQLUniqueDelegate( this );
	private final MySQLStorageEngine storageEngine = createStorageEngine();
	private final SizeStrategy sizeStrategy = new SizeStrategyImpl() {
		@Override
		public Size resolveSize(
				JdbcType jdbcType,
				JavaType<?> javaType,
				Integer precision,
				Integer scale,
				Long length) {
			switch ( jdbcType.getDefaultSqlTypeCode() ) {
				case Types.BIT:
					// MySQL allows BIT with a length up to 64 (less the default length 255)
					if ( length != null ) {
						return Size.length( Math.min( Math.max( length, 1 ), 64 ) );
					}
			}
			return super.resolveSize( jdbcType, javaType, precision, scale, length );
		}
	};

	private final int maxVarcharLength;
	private final int maxVarbinaryLength;

	public MySQLDialect() {
		this( MINIMUM_VERSION );
	}

	public MySQLDialect(DatabaseVersion version) {
		this( version, 4 );
	}

	public MySQLDialect(DatabaseVersion version, int bytesPerCharacter) {
		super( version );
		maxVarcharLength = maxVarcharLength( getMySQLVersion(), bytesPerCharacter ); //conservative assumption
		maxVarbinaryLength = maxVarbinaryLength( getMySQLVersion() );
	}

	public MySQLDialect(DialectResolutionInfo info) {
		this( createVersion( info ), getCharacterSetBytesPerCharacter( info.getDatabaseMetadata() ) );
		registerKeywords( info );
	}

	protected static DatabaseVersion createVersion(DialectResolutionInfo info) {
		final String versionString = info.getDatabaseVersion();
		if ( versionString != null ) {
			final String[] components = versionString.split( "\\." );
			if ( components.length >= 3 ) {
				try {
					final int majorVersion = Integer.parseInt( components[0] );
					final int minorVersion = Integer.parseInt( components[1] );
					final int patchLevel = Integer.parseInt( components[2] );
					return DatabaseVersion.make( majorVersion, minorVersion, patchLevel );
				}
				catch (NumberFormatException ex) {
					// Ignore
				}
			}
		}
		return info.makeCopy();
	}

	@Override
	protected DatabaseVersion getMinimumSupportedVersion() {
		return MINIMUM_VERSION;
	}

	@Override
	protected void initDefaultProperties() {
		super.initDefaultProperties();
		getDefaultProperties().setProperty( Environment.MAX_FETCH_DEPTH, "2" );
	}

	private MySQLStorageEngine createStorageEngine() {
		String storageEngine = Environment.getProperties().getProperty( Environment.STORAGE_ENGINE );
		if (storageEngine == null) {
			storageEngine = System.getProperty( Environment.STORAGE_ENGINE );
		}
		if (storageEngine == null) {
			return getDefaultMySQLStorageEngine();
		}
		else if( "innodb".equalsIgnoreCase( storageEngine ) ) {
			return InnoDBStorageEngine.INSTANCE;
		}
		else if( "myisam".equalsIgnoreCase( storageEngine ) ) {
			return MyISAMStorageEngine.INSTANCE;
		}
		else {
			throw new UnsupportedOperationException( "The " + storageEngine + " storage engine is not supported" );
		}
	}

	@Override
	protected String columnType(int sqlTypeCode) {
		switch ( sqlTypeCode ) {
			case BOOLEAN:
				// HHH-6935: Don't use "boolean" i.e. tinyint(1) due to JDBC ResultSetMetaData
				return "bit";

			case TIMESTAMP:
				return "datetime($p)";
			case TIMESTAMP_WITH_TIMEZONE:
				return "timestamp($p)";
			case NUMERIC:
				// it's just a synonym
				return columnType( DECIMAL );
			// the maximum long LOB length is 4_294_967_295, bigger than any Java string
			case BLOB:
				return "longblob";
			case NCLOB:
			case CLOB:
				return "longtext";

			default:
				return super.columnType( sqlTypeCode );
		}
	}

	@Override
	protected String castType(int sqlTypeCode) {
		switch ( sqlTypeCode ) {
			case BOOLEAN:
			case BIT:
				//special case for casting to Boolean
				return "unsigned";
			case TINYINT:
			case SMALLINT:
			case INTEGER:
			case BIGINT:
				//MySQL doesn't let you cast to INTEGER/BIGINT/TINYINT
				return "signed";
			case FLOAT:
			case REAL:
			case DOUBLE:
				//MySQL doesn't let you cast to DOUBLE/FLOAT
				//but don't just return 'decimal' because
				//the default scale is 0 (no decimal places)
				return "decimal($p,$s)";
			case CHAR:
			case NCHAR:
			case VARCHAR:
			case NVARCHAR:
			case LONG32VARCHAR:
			case LONG32NVARCHAR:
				//MySQL doesn't let you cast to TEXT/LONGTEXT
				return "char";
			case BINARY:
			case VARBINARY:
			case LONG32VARBINARY:
				//MySQL doesn't let you cast to BLOB/TINYBLOB/LONGBLOB
				return "binary";
		}
		return super.castType( sqlTypeCode );
	}

	@Override
	protected void registerColumnTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
		super.registerColumnTypes( typeContributions, serviceRegistry );
		final DdlTypeRegistry ddlTypeRegistry = typeContributions.getTypeConfiguration().getDdlTypeRegistry();

		// MySQL 5.7 brings JSON native support with a dedicated datatype
		// https://dev.mysql.com/doc/refman/5.7/en/json.html
		ddlTypeRegistry.addDescriptor( new DdlTypeImpl( JSON, "json", this ) );

		ddlTypeRegistry.addDescriptor( new DdlTypeImpl( GEOMETRY, "geometry", this ) );

		// MySQL has approximately one million text and blob types. We have
		// already registered longtext + longblob via the regular method,
		// but we still need to do the rest of them here.

		final int maxTinyLobLen = 255;
		final int maxLobLen = 65_535;
		final int maxMediumLobLen = 16_777_215;

		final CapacityDependentDdlType.Builder varcharBuilder = CapacityDependentDdlType.builder(
						VARCHAR,
						columnType( CLOB ),
						"char",
						this
				)
				.withTypeCapacity( getMaxVarcharLength(), "varchar($l)" )
				.withTypeCapacity( maxMediumLobLen, "mediumtext" );
		if ( getMaxVarcharLength() < maxLobLen ) {
			varcharBuilder.withTypeCapacity( maxLobLen, "text" );
		}
		ddlTypeRegistry.addDescriptor( varcharBuilder.build() );

		final CapacityDependentDdlType.Builder nvarcharBuilder = CapacityDependentDdlType.builder(
						NVARCHAR,
						columnType( NCLOB ),
						"char",
						this
				)
				.withTypeCapacity( getMaxVarcharLength(), "varchar($l)" )
				.withTypeCapacity( maxMediumLobLen, "mediumtext" );
		if ( getMaxVarcharLength() < maxLobLen ) {
			nvarcharBuilder.withTypeCapacity( maxLobLen, "text" );
		}
		ddlTypeRegistry.addDescriptor( nvarcharBuilder.build() );

		final CapacityDependentDdlType.Builder varbinaryBuilder = CapacityDependentDdlType.builder(
						VARBINARY,
						columnType( BLOB ),
						"binary",
						this
				)
				.withTypeCapacity( getMaxVarbinaryLength(), "varbinary($l)" )
				.withTypeCapacity( maxMediumLobLen, "mediumblob" );
		if ( getMaxVarcharLength() < maxLobLen ) {
			varbinaryBuilder.withTypeCapacity( maxLobLen, "blob" );
		}
		ddlTypeRegistry.addDescriptor( varbinaryBuilder.build() );

		ddlTypeRegistry.addDescriptor( new DdlTypeImpl( LONG32VARBINARY, columnType( BLOB ), "binary", this ) );
		ddlTypeRegistry.addDescriptor( new DdlTypeImpl( LONG32VARCHAR, columnType( CLOB ), "char", this ) );
		ddlTypeRegistry.addDescriptor( new DdlTypeImpl( LONG32NVARCHAR, columnType( CLOB ), "char", this ) );

		ddlTypeRegistry.addDescriptor(
				CapacityDependentDdlType.builder( BLOB, columnType( BLOB ), "binary", this )
						.withTypeCapacity( maxTinyLobLen, "tinyblob" )
						.withTypeCapacity( maxMediumLobLen, "mediumblob" )
						.withTypeCapacity( maxLobLen, "blob" )
						.build()
		);

		ddlTypeRegistry.addDescriptor(
				CapacityDependentDdlType.builder( CLOB, columnType( CLOB ), "char", this )
						.withTypeCapacity( maxTinyLobLen, "tinytext" )
						.withTypeCapacity( maxMediumLobLen, "mediumtext" )
						.withTypeCapacity( maxLobLen, "text" )
						.build()
		);

		ddlTypeRegistry.addDescriptor(
				CapacityDependentDdlType.builder( NCLOB, columnType( NCLOB ), "char", this )
						.withTypeCapacity( maxTinyLobLen, "tinytext" )
						.withTypeCapacity( maxMediumLobLen, "mediumtext" )
						.withTypeCapacity( maxLobLen, "text" )
						.build()
		);
	}

	protected static int getCharacterSetBytesPerCharacter(DatabaseMetaData databaseMetaData) {
		if ( databaseMetaData != null ) {
			try (java.sql.Statement s = databaseMetaData.getConnection().createStatement() ) {
				final ResultSet rs = s.executeQuery( "SELECT @@character_set_database" );
				if ( rs.next() ) {
					final String characterSet = rs.getString( 1 );
					final int collationIndex = characterSet.indexOf( '_' );
					// According to https://dev.mysql.com/doc/refman/8.0/en/charset-charsets.html
					switch ( collationIndex == -1 ? characterSet : characterSet.substring( 0, collationIndex ) ) {
						case "utf16":
						case "utf16le":
						case "utf32":
						case "utf8mb4":
						case "gb18030":
							return 4;
						case "utf8":
						case "utf8mb3":
						case "eucjpms":
						case "ujis":
							return 3;
						case "ucs2":
						case "cp932":
						case "big5":
						case "euckr":
						case "gb2312":
						case "gbk":
						case "sjis":
							return 2;
						default:
							return 1;
					}
				}
			}
			catch (SQLException ex) {
				// Ignore
			}
		}
		return 4;
	}

	private static int maxVarbinaryLength(DatabaseVersion version) {
		return 65_535;
	}

	private static int maxVarcharLength(DatabaseVersion version, int bytesPerCharacter) {
		switch ( bytesPerCharacter ) {
			case 1:
				return 65_535;
			case 2:
				return 32_767;
			case 3:
				return 21_844;
			case 4:
			default:
				return 16_383;
		}
	}

	@Override
	public int getMaxVarcharLength() {
		return maxVarcharLength;
	}

	@Override
	public int getMaxVarbinaryLength() {
		return maxVarbinaryLength;
	}

	@Override
	public String getNullColumnString(String columnType) {
		// Good job MySQL https://dev.mysql.com/doc/refman/8.0/en/timestamp-initialization.html
		// If the explicit_defaults_for_timestamp system variable is enabled, TIMESTAMP columns permit NULL values only if declared with the NULL attribute.
		if ( columnType.regionMatches( true, 0, "timestamp", 0, "timestamp".length() ) ) {
			return " null";
		}
		return super.getNullColumnString( columnType );
	}

	public DatabaseVersion getMySQLVersion() {
		return super.getVersion();
	}

	@Override
	public SizeStrategy getSizeStrategy() {
		return sizeStrategy;
	}

	@Override
	public long getDefaultLobLength() {
		//max length for mediumblob or mediumtext
		return 16_777_215;
	}

	@Override
	public JdbcType resolveSqlTypeDescriptor(
			String columnTypeName,
			int jdbcTypeCode,
			int precision,
			int scale,
			JdbcTypeRegistry jdbcTypeRegistry) {
		if ( jdbcTypeCode == Types.BIT ) {
			return jdbcTypeRegistry.getDescriptor( Types.BOOLEAN );
		}
		return super.resolveSqlTypeDescriptor(
				columnTypeName,
				jdbcTypeCode,
				precision,
				scale,
				jdbcTypeRegistry
		);
	}

	@Override
	public int getPreferredSqlTypeCodeForBoolean() {
		return Types.BIT;
	}

//	@Override
//	public int getDefaultDecimalPrecision() {
//		//this is the maximum, but I guess it's too high
//		return 65;
//	}

	@Override
	public void initializeFunctionRegistry(QueryEngine queryEngine) {
		super.initializeFunctionRegistry( queryEngine );

		CommonFunctionFactory functionFactory = new CommonFunctionFactory(queryEngine);

		functionFactory.soundex();
		functionFactory.radians();
		functionFactory.degrees();
		functionFactory.cot();
		functionFactory.log();
		functionFactory.log2();
		functionFactory.log10();
		functionFactory.trim2();
		functionFactory.octetLength();
		functionFactory.reverse();
		functionFactory.space();
		functionFactory.repeat();
		functionFactory.pad_space();
		functionFactory.md5();
		functionFactory.yearMonthDay();
		functionFactory.hourMinuteSecond();
		functionFactory.dayofweekmonthyear();
		functionFactory.weekQuarter();
		functionFactory.daynameMonthname();
		functionFactory.lastDay();
		functionFactory.date();
		functionFactory.timestamp();
		time( queryEngine );

		functionFactory.utcDateTimeTimestamp();
		functionFactory.rand();
		functionFactory.crc32();
		functionFactory.sha1();
		functionFactory.sha2();
		functionFactory.sha();
		functionFactory.bitLength();
		functionFactory.octetLength();
		functionFactory.ascii();
		functionFactory.instr();
		functionFactory.substr();
		//also natively supports ANSI-style substring()
		functionFactory.position();
		functionFactory.nowCurdateCurtime();
		functionFactory.truncate();
		functionFactory.insert();
		functionFactory.bitandorxornot_operator();
		functionFactory.bitAndOr();
		functionFactory.stddev();
		functionFactory.stddevPopSamp();
		functionFactory.variance();
		functionFactory.varPopSamp();
		functionFactory.datediff();
		functionFactory.adddateSubdateAddtimeSubtime();
		functionFactory.format_dateFormat();
		functionFactory.makedateMaketime();
		functionFactory.localtimeLocaltimestamp();

		BasicTypeRegistry basicTypeRegistry = queryEngine.getTypeConfiguration().getBasicTypeRegistry();

		SqmFunctionRegistry functionRegistry = queryEngine.getSqmFunctionRegistry();

		functionRegistry.noArgsBuilder( "localtime" )
				.setInvariantType(basicTypeRegistry.resolve( StandardBasicTypes.TIMESTAMP ))
				.setUseParenthesesWhenNoArgs( false )
				.register();

		// pi() produces a value with 7 digits unless we're explicit
		if ( getMySQLVersion().isSameOrAfter( 8 ) ) {
			functionRegistry.patternDescriptorBuilder( "pi", "cast(pi() as double)" )
					.setInvariantType( basicTypeRegistry.resolve( StandardBasicTypes.DOUBLE ) )
					.setExactArgumentCount( 0 )
					.setArgumentListSignature( "" )
					.register();
		}
		else {
			// But before MySQL 8, it's not possible to cast to double. Double has a default precision of 53
			// and since the internal representation of pi has only 15 decimal places, we cast to decimal(53,15)
			functionRegistry.patternDescriptorBuilder( "pi", "cast(pi() as decimal(53,15))" )
					.setInvariantType( basicTypeRegistry.resolve( StandardBasicTypes.DOUBLE ) )
					.setExactArgumentCount( 0 )
					.setArgumentListSignature( "" )
					.register();
		}

		// By default char() produces a binary string, not a character string.
		// (Note also that char() is actually a variadic function in MySQL.)
		functionRegistry.patternDescriptorBuilder( "chr", "char(?1 using ascii)" )
				.setInvariantType(basicTypeRegistry.resolve( StandardBasicTypes.CHARACTER ))
				.setExactArgumentCount(1)
				.setParameterTypes(FunctionParameterType.INTEGER)
				.register();
		functionRegistry.registerAlternateKey( "char", "chr" );

		// MySQL timestamp type defaults to precision 0 (seconds) but
		// we want the standard default precision of 6 (microseconds)
		functionFactory.sysdateExplicitMicros();
		if ( getMySQLVersion().isSameOrAfter( 8, 0, 2 ) ) {
			functionFactory.windowFunctions();
			if ( getMySQLVersion().isSameOrAfter( 8, 0, 11 ) ) {
				functionFactory.hypotheticalOrderedSetAggregates_windowEmulation();
			}
		}

		functionFactory.listagg_groupConcat();
	}

	@Override
	public void contributeTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
		super.contributeTypes( typeContributions, serviceRegistry );

		final JdbcTypeRegistry jdbcTypeRegistry = typeContributions.getTypeConfiguration()
				.getJdbcTypeRegistry();

		jdbcTypeRegistry.addDescriptorIfAbsent( SqlTypes.JSON, JsonJdbcType.INSTANCE );

		// MySQL requires a custom binder for binding untyped nulls with the NULL type
		typeContributions.contributeJdbcType( NullJdbcType.INSTANCE );

		// Until we remove StandardBasicTypes, we have to keep this
		typeContributions.contributeType(
				new NullType(
						NullJdbcType.INSTANCE,
						typeContributions.getTypeConfiguration()
								.getJavaTypeRegistry()
								.getDescriptor( Object.class )
				)
		);
	}

	@Override
	public SqlAstTranslatorFactory getSqlAstTranslatorFactory() {
		return new StandardSqlAstTranslatorFactory() {
			@Override
			protected <T extends JdbcOperation> SqlAstTranslator<T> buildTranslator(
					SessionFactoryImplementor sessionFactory, Statement statement) {
				return new MySQLSqlAstTranslator<>( sessionFactory, statement );
			}
		};
	}

	@Override
	public String castPattern(CastType from, CastType to) {
		if ( to == CastType.INTEGER_BOOLEAN ) {
			switch ( from ) {
				case STRING:
				case INTEGER:
				case LONG:
				case YN_BOOLEAN:
				case TF_BOOLEAN:
				case BOOLEAN:
					break;
				default:
					// MySQL/MariaDB don't support casting to bit
					return "abs(sign(?1))";
			}
		}
		return super.castPattern( from, to );
	}

	private void time(QueryEngine queryEngine) {
		queryEngine.getSqmFunctionRegistry().namedDescriptorBuilder( "time" )
				.setExactArgumentCount( 1 )
				.setInvariantType(
					queryEngine.getTypeConfiguration().getBasicTypeRegistry().resolve( StandardBasicTypes.STRING )
				)
				.register();
	}

	@Override
	public int getFloatPrecision() {
		//according to MySQL docs, this is
		//the maximum precision for 4 bytes
		return 23;
	}

	/**
	 * MySQL 5.7 precision defaults to seconds, but microseconds is better
	 */
	@Override
	public String currentTimestamp() {
		return "current_timestamp(6)";
	}

	// for consistency, we could do this: but I decided not to
	// because it seems to me that fractional seconds can't possibly
	// be meaningful in a time, as opposed to a timestamp
//	@Override
//	public String currentTime() {
//		return getMySQLVersion().isBefore( 5, 7 ) ? super.currentTimestamp() : "current_time(6)";
//	}

	/**
	 * {@code microsecond} is the smallest unit for
	 * {@code timestampadd()} and {@code timestampdiff()},
	 * and the highest precision for a {@code timestamp}.
	 */
	@Override
	public long getFractionalSecondPrecisionInNanos() {
		return 1_000; //microseconds
	}

	/**
	 * MySQL supports a limited list of temporal fields in the
	 * extract() function, but we can emulate some of them by
	 * using the appropriate named functions instead of
	 * extract().
	 *
	 * Thus, the additional supported fields are
	 * {@link TemporalUnit#DAY_OF_YEAR},
	 * {@link TemporalUnit#DAY_OF_MONTH},
	 * {@link TemporalUnit#DAY_OF_YEAR}.
	 *
	 * In addition, the field {@link TemporalUnit#SECOND} is
	 * redefined to include microseconds.
	 */
	@Override
	public String extractPattern(TemporalUnit unit) {
		switch (unit) {
			case SECOND:
				return "(second(?2)+microsecond(?2)/1e6)";
			case WEEK:
				return "weekofyear(?2)"; //same as week(?2,3), the ISO week
			case DAY_OF_WEEK:
				return "dayofweek(?2)";
			case DAY_OF_MONTH:
				return "dayofmonth(?2)";
			case DAY_OF_YEAR:
				return "dayofyear(?2)";
			//TODO: case WEEK_YEAR: yearweek(?2, 3)/100
			default:
				return "?1(?2)";
		}
	}

	@Override
	public String timestampaddPattern(TemporalUnit unit, TemporalType temporalType, IntervalType intervalType) {
		switch (unit) {
			case NANOSECOND:
				return "timestampadd(microsecond,(?2)/1e3,?3)";
			case NATIVE:
				return "timestampadd(microsecond,?2,?3)";
			default:
				return "timestampadd(?1,?2,?3)";
		}
	}

	@Override
	public String timestampdiffPattern(TemporalUnit unit, TemporalType fromTemporalType, TemporalType toTemporalType) {
		switch (unit) {
			case NANOSECOND:
				return "timestampdiff(microsecond,?2,?3)*1e3";
			case NATIVE:
				return "timestampdiff(microsecond,?2,?3)";
			default:
				return "timestampdiff(?1,?2,?3)";
		}
	}

	@Override
	public boolean supportsUnionAll() {
		return true;
	}

	@Override
	public SelectItemReferenceStrategy getGroupBySelectItemReferenceStrategy() {
		return SelectItemReferenceStrategy.POSITION;
	}

	@Override
	public boolean supportsColumnCheck() {
		return false;
	}

	@Override
	public String getQueryHintString(String query, String hints) {
		return IndexQueryHintHandler.INSTANCE.addQueryHints( query, hints );
	}

	/**
	 * No support for sequences.
	 */
	@Override
	public SequenceSupport getSequenceSupport() {
		return NoSequenceSupport.INSTANCE;
	}

	public ViolatedConstraintNameExtractor getViolatedConstraintNameExtractor() {
		return EXTRACTOR;
	}

	private static final ViolatedConstraintNameExtractor EXTRACTOR =
			new TemplatedViolatedConstraintNameExtractor( sqle -> {
				final String sqlState = JdbcExceptionHelper.extractSqlState( sqle );
				if ( sqlState != null ) {
					switch ( Integer.parseInt( sqlState ) ) {
						case 23000:
							return extractUsingTemplate( " for key '", "'", sqle.getMessage() );
					}
				}
				return null;
			} );

	@Override
	public boolean qualifyIndexName() {
		return false;
	}

	@Override
	public String getAddForeignKeyConstraintString(
			String constraintName,
			String[] foreignKey,
			String referencedTable,
			String[] primaryKey,
			boolean referencesPrimaryKey) {
		final String cols = String.join( ", ", foreignKey );
		final String referencedCols = String.join( ", ", primaryKey );
		return String.format(
				" add constraint %s foreign key (%s) references %s (%s)",
				constraintName,
				cols,
				referencedTable,
				referencedCols
		);
	}

	@Override
	public String getDropForeignKeyString() {
		return " drop foreign key ";
	}

	@Override
	public LimitHandler getLimitHandler() {
		//also supports LIMIT n OFFSET m
		return LimitLimitHandler.INSTANCE;
	}

	@Override
	public char closeQuote() {
		return '`';
	}

	@Override
	public char openQuote() {
		return '`';
	}

	@Override
	public boolean canCreateCatalog() {
		return true;
	}

	@Override
	public String[] getCreateCatalogCommand(String catalogName) {
		return new String[] { "create database " + catalogName };
	}

	@Override
	public String[] getDropCatalogCommand(String catalogName) {
		return new String[] { "drop database " + catalogName };
	}

	@Override
	public boolean canCreateSchema() {
		return false;
	}

	@Override
	public String[] getCreateSchemaCommand(String schemaName) {
		throw new UnsupportedOperationException( "MySQL does not support dropping creating/dropping schemas in the JDBC sense" );
	}

	@Override
	public String[] getDropSchemaCommand(String schemaName) {
		throw new UnsupportedOperationException( "MySQL does not support dropping creating/dropping schemas in the JDBC sense" );
	}

	@Override
	public boolean supportsIfExistsBeforeTableName() {
		return true;
	}

	@Override
	public String getSelectGUIDString() {
		return "select uuid()";
	}

	@Override
	public String getTableComment(String comment) {
		return " comment='" + comment + "'";
	}

	@Override
	public String getColumnComment(String comment) {
		return " comment '" + comment + "'";
	}

	@Override
	public NullOrdering getNullOrdering() {
		return NullOrdering.SMALLEST;
	}

	@Override
	public SqmMultiTableMutationStrategy getFallbackSqmMutationStrategy(
			EntityMappingType rootEntityDescriptor,
			RuntimeModelCreationContext runtimeModelCreationContext) {

		return new LocalTemporaryTableMutationStrategy(
				TemporaryTable.createIdTable(
						rootEntityDescriptor,
						basename -> TemporaryTable.ID_TABLE_PREFIX + basename,
						this,
						runtimeModelCreationContext
				),
				runtimeModelCreationContext.getSessionFactory()
		);
	}

	@Override
	public SqmMultiTableInsertStrategy getFallbackSqmInsertStrategy(
			EntityMappingType rootEntityDescriptor,
			RuntimeModelCreationContext runtimeModelCreationContext) {

		return new LocalTemporaryTableInsertStrategy(
				TemporaryTable.createEntityTable(
						rootEntityDescriptor,
						name -> TemporaryTable.ENTITY_TABLE_PREFIX + name,
						this,
						runtimeModelCreationContext
				),
				runtimeModelCreationContext.getSessionFactory()
		);
	}

	@Override
	public TemporaryTableKind getSupportedTemporaryTableKind() {
		return TemporaryTableKind.LOCAL;
	}

	@Override
	public String getTemporaryTableCreateCommand() {
		return "create temporary table if not exists";
	}

	@Override
	public String getTemporaryTableDropCommand() {
		return "drop temporary table";
	}

	@Override
	public AfterUseAction getTemporaryTableAfterUseAction() {
		return AfterUseAction.DROP;
	}

	@Override
	public BeforeUseAction getTemporaryTableBeforeUseAction() {
		return BeforeUseAction.CREATE;
	}

	@Override
	public int getMaxAliasLength() {
		// Max alias length is 256, but Hibernate needs to add "uniqueing info" so we account for that
		return 246;
	}

	@Override
	public int getMaxIdentifierLength() {
		return 64;
	}

	@Override
	public boolean supportsCurrentTimestampSelection() {
		return true;
	}

	@Override
	public boolean isCurrentTimestampSelectStringCallable() {
		return false;
	}

	@Override
	public String getCurrentTimestampSelectString() {
		return "select now()";
	}

	@Override
	public int registerResultSetOutParameter(CallableStatement statement, int col) throws SQLException {
		return col;
	}

	@Override
	public ResultSet getResultSet(CallableStatement ps) throws SQLException {
		boolean isResultSet = ps.execute();
		while ( !isResultSet && ps.getUpdateCount() != -1 ) {
			isResultSet = ps.getMoreResults();
		}
		return ps.getResultSet();
	}

	@Override
	public UniqueDelegate getUniqueDelegate() {
		return uniqueDelegate;
	}

	@Override
	public boolean supportsNullPrecedence() {
		return false;
	}

	// Overridden informational metadata ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public boolean supportsLobValueChangePropagation() {
		// note: at least my local MySQL 5.1 install shows this not working...
		return false;
	}

	@Override
	public boolean supportsSubqueryOnMutatingTable() {
		return false;
	}

	@Override
	public boolean supportsLockTimeouts() {
		// yes, we do handle "lock timeout" conditions in the exception conversion delegate,
		// but that's a hardcoded lock timeout period across the whole entire MySQL database.
		// MySQL does not support specifying lock timeouts as part of the SQL statement, which is really
		// what this meta method is asking.
		return false;
	}

	@Override
	public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
		return (sqlException, message, sql) -> {
			switch ( sqlException.getErrorCode() ) {
				case 1205:
				case 3572:
					return new PessimisticLockException( message, sqlException, sql );
				case 1207:
				case 1206:
					return new LockAcquisitionException( message, sqlException, sql );
			}

			final String sqlState = JdbcExceptionHelper.extractSqlState( sqlException );
			if ( sqlState != null ) {
				switch ( sqlState ) {
					case "41000":
						return new LockTimeoutException( message, sqlException, sql );
					case "40001":
						return new LockAcquisitionException( message, sqlException, sql );
				}
			}

			return null;
		};
	}

	@Override
	public NameQualifierSupport getNameQualifierSupport() {
		return NameQualifierSupport.CATALOG;
	}

	@Override
	public IdentifierHelper buildIdentifierHelper(IdentifierHelperBuilder builder, DatabaseMetaData dbMetaData)
			throws SQLException {

		if ( dbMetaData == null ) {
			builder.setUnquotedCaseStrategy( IdentifierCaseStrategy.MIXED );
			builder.setQuotedCaseStrategy( IdentifierCaseStrategy.MIXED );
		}

		return super.buildIdentifierHelper( builder, dbMetaData );
	}

	@Override
	public IdentityColumnSupport getIdentityColumnSupport() {
		return new MySQLIdentityColumnSupport();
	}

	@Override
	public boolean isJdbcLogWarningsEnabledByDefault() {
		return false;
	}

	@Override
	public boolean supportsCascadeDelete() {
		return storageEngine.supportsCascadeDelete();
	}

	@Override
	public String getTableTypeString() {
		return storageEngine.getTableTypeString( "engine" );
	}

	@Override
	public boolean hasSelfReferentialForeignKeyBug() {
		return storageEngine.hasSelfReferentialForeignKeyBug();
	}

	@Override
	public boolean dropConstraints() {
		return storageEngine.dropConstraints();
	}

	protected MySQLStorageEngine getDefaultMySQLStorageEngine() {
		return InnoDBStorageEngine.INSTANCE;
	}

	@Override
	public void appendLiteral(SqlAppender appender, String literal) {
		appender.appendSql( '\'' );
		for ( int i = 0; i < literal.length(); i++ ) {
			final char c = literal.charAt( i );
			switch ( c ) {
				case '\'':
					appender.appendSql( '\'' );
					break;
				case '\\':
					appender.appendSql( '\\' );
					break;
			}
			appender.appendSql( c );
		}
		appender.appendSql( '\'' );
	}

	@Override
	public void appendDatetimeFormat(SqlAppender appender, String format) {
		appender.appendSql( datetimeFormat( format ).result() );
	}

	public static Replacer datetimeFormat(String format) {
		return new Replacer( format, "'", "" )
				.replace("%", "%%")

				//year
				.replace("yyyy", "%Y")
				.replace("yyy", "%Y")
				.replace("yy", "%y")
				.replace("y", "%Y")

				//month of year
				.replace("MMMM", "%M")
				.replace("MMM", "%b")
				.replace("MM", "%m")
				.replace("M", "%c")

				//week of year
				.replace("ww", "%v")
				.replace("w", "%v")
				//year for week
				.replace("YYYY", "%x")
				.replace("YYY", "%x")
				.replace("YY", "%x")
				.replace("Y", "%x")

				//week of month
				//????

				//day of week
				.replace("EEEE", "%W")
				.replace("EEE", "%a")
				.replace("ee", "%w")
				.replace("e", "%w")

				//day of month
				.replace("dd", "%d")
				.replace("d", "%e")

				//day of year
				.replace("DDD", "%j")
				.replace("DD", "%j")
				.replace("D", "%j")

				//am pm
				.replace("a", "%p")

				//hour
				.replace("hh", "%I")
				.replace("HH", "%H")
				.replace("h", "%l")
				.replace("H", "%k")

				//minute
				.replace("mm", "%i")
				.replace("m", "%i")

				//second
				.replace("ss", "%S")
				.replace("s", "%S")

				//fractional seconds
				.replace("SSSSSS", "%f")
				.replace("SSSSS", "%f")
				.replace("SSSS", "%f")
				.replace("SSS", "%f")
				.replace("SS", "%f")
				.replace("S", "%f");
	}

	private String withTimeout(String lockString, int timeout) {
		switch (timeout) {
			case LockOptions.NO_WAIT:
				return supportsNoWait() ? lockString + " nowait" : lockString;
			case LockOptions.SKIP_LOCKED:
				return supportsSkipLocked() ? lockString + " skip locked" : lockString;
			case LockOptions.WAIT_FOREVER:
				return lockString;
			default:
				return supportsWait() ? lockString + " wait " + timeout : lockString;
		}
	}

	@Override
	public String getWriteLockString(int timeout) {
		return withTimeout( getForUpdateString(), timeout );
	}

	@Override
	public String getWriteLockString(String aliases, int timeout) {
		return withTimeout( getForUpdateString(aliases), timeout );
	}

	@Override
	public String getReadLockString(int timeout) {
		return withTimeout( supportsForShare() ? " for share" : " lock in share mode", timeout );
	}

	@Override
	public String getReadLockString(String aliases, int timeout) {
		if ( supportsAliasLocks() && supportsForShare() ) {
			return withTimeout(" for share of " + aliases, timeout );
		}
		else {
			// fall back to locking all aliases
			return getReadLockString( timeout );
		}
	}

	@Override
	public String getForUpdateSkipLockedString() {
		return supportsSkipLocked()
				? " for update skip locked"
				: getForUpdateString();
	}

	@Override
	public String getForUpdateSkipLockedString(String aliases) {
		return supportsSkipLocked() && supportsAliasLocks()
				? getForUpdateString( aliases ) + " skip locked"
				// fall back to skip locking all aliases
				: getForUpdateSkipLockedString();
	}

	@Override
	public String getForUpdateNowaitString() {
		return supportsNoWait()
				? " for update nowait"
				: getForUpdateString();
	}

	@Override
	public String getForUpdateNowaitString(String aliases) {
		return supportsNoWait() && supportsAliasLocks()
				? getForUpdateString( aliases ) + " nowait"
				// fall back to nowait locking all aliases
				: getForUpdateNowaitString();
	}

	@Override
	public String getForUpdateString(String aliases) {
		return supportsAliasLocks()
				? " for update of " + aliases
				// fall back to locking all aliases
				: getForUpdateString();
	}

	@Override
	public boolean supportsOffsetInSubquery() {
		return true;
	}

	@Override
	public boolean supportsWindowFunctions() {
		return getMySQLVersion().isSameOrAfter( 8, 0, 2 );
	}

	@Override
	public boolean supportsLateral() {
		return getMySQLVersion().isSameOrAfter( 8, 0, 14 );
	}

	@Override
	public boolean supportsRecursiveCTE() {
		return getMySQLVersion().isSameOrAfter( 8, 0, 14 );
	}

	@Override
	public boolean supportsSkipLocked() {
		return getMySQLVersion().isSameOrAfter( 8 );
	}

	@Override
	public boolean supportsNoWait() {
		return getMySQLVersion().isSameOrAfter( 8 );
	}

	@Override
	public boolean supportsWait() {
		//only supported on MariaDB
		return false;
	}

	@Override
	public RowLockStrategy getWriteRowLockStrategy() {
		return supportsAliasLocks() ? RowLockStrategy.TABLE : RowLockStrategy.NONE;
	}

	@Override
	protected void registerDefaultKeywords() {
		super.registerDefaultKeywords();
		registerKeyword( "key" );
	}

	boolean supportsForShare() {
		return getMySQLVersion().isSameOrAfter( 8 );
	}

	boolean supportsAliasLocks() {
		return getMySQLVersion().isSameOrAfter( 8 );
	}

}
