/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.mapping;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.hibernate.MappingException;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.MetadataBuildingContext;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.internal.util.ReflectHelper;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.internal.util.collections.SingletonIterator;
import org.hibernate.persister.entity.EntityPersister;

/**
 * A mapping model object that represents the root class in an entity class
 * {@linkplain jakarta.persistence.Inheritance inheritance} hierarchy.
 *
 * @author Gavin King
 */
public class RootClass extends PersistentClass implements TableOwner {
	private static final CoreMessageLogger LOG = CoreLogging.messageLogger( RootClass.class );

	public static final String DEFAULT_IDENTIFIER_COLUMN_NAME = "id";
	public static final String DEFAULT_DISCRIMINATOR_COLUMN_NAME = "class";

	private Property identifierProperty;
	private KeyValue identifier;
	private Property version;
	private boolean polymorphic;

	private String cacheConcurrencyStrategy;
	private String cacheRegionName;
	private boolean lazyPropertiesCacheable = true;
	private String naturalIdCacheRegionName;

	private Value discriminator;
	private boolean mutable = true;
	private boolean embeddedIdentifier;
	private boolean explicitPolymorphism;
	private Class<? extends EntityPersister> entityPersisterClass;
	private boolean forceDiscriminator;
	private String where;
	private Table table;
	private boolean discriminatorInsertable = true;
	private int nextSubclassId;
	private Property declaredIdentifierProperty;
	private Property declaredVersion;

	public RootClass(MetadataBuildingContext buildingContext) {
		super( buildingContext );
	}

	@Override
	int nextSubclassId() {
		return ++nextSubclassId;
	}

	@Override
	public int getSubclassId() {
		return 0;
	}

	public void setTable(Table table) {
		this.table = table;
	}

	@Override
	public Table getTable() {
		return table;
	}

	@Override
	public Property getIdentifierProperty() {
		return identifierProperty;
	}

	@Override
	public Property getDeclaredIdentifierProperty() {
		return declaredIdentifierProperty;
	}

	public void setDeclaredIdentifierProperty(Property declaredIdentifierProperty) {
		this.declaredIdentifierProperty = declaredIdentifierProperty;
	}

	@Override
	public KeyValue getIdentifier() {
		return identifier;
	}

	@Override
	public boolean hasIdentifierProperty() {
		return identifierProperty != null;
	}

	@Override
	public Value getDiscriminator() {
		return discriminator;
	}

	@Override
	public boolean isInherited() {
		return false;
	}

	@Override
	public boolean isPolymorphic() {
		return polymorphic;
	}

	public void setPolymorphic(boolean polymorphic) {
		this.polymorphic = polymorphic;
	}

	@Override
	public RootClass getRootClass() {
		return this;
	}

	@Override @Deprecated
	public Iterator<Property> getPropertyClosureIterator() {
		return getPropertyIterator();
	}

	@Override
	public List<Property> getPropertyClosure() {
		return getProperties();
	}

	@Override @Deprecated
	public Iterator<Table> getTableClosureIterator() {
		return new SingletonIterator<>( getTable() );
	}

	@Override
	public List<Table> getTableClosure() {
		return List.of( getTable() );
	}

	@Override @Deprecated
	public Iterator<KeyValue> getKeyClosureIterator() {
		return new SingletonIterator<>( getKey() );
	}

	@Override
	public List<KeyValue> getKeyClosure() {
		return List.of( getKey() );
	}

	@Override
	public void addSubclass(Subclass subclass) throws MappingException {
		super.addSubclass( subclass );
		setPolymorphic( true );
	}

	@Override
	public boolean isExplicitPolymorphism() {
		return explicitPolymorphism;
	}

	@Override
	public Property getVersion() {
		return version;
	}

	@Override
	public Property getDeclaredVersion() {
		return declaredVersion;
	}

	public void setDeclaredVersion(Property declaredVersion) {
		this.declaredVersion = declaredVersion;
	}

	public void setVersion(Property version) {
		this.version = version;
	}

	@Override
	public boolean isVersioned() {
		return version != null;
	}

	@Override
	public boolean isMutable() {
		return mutable;
	}

	@Override
	public boolean hasEmbeddedIdentifier() {
		return embeddedIdentifier;
	}

	@Override
	public Class<? extends EntityPersister> getEntityPersisterClass() {
		return entityPersisterClass;
	}

	@Override
	public Table getRootTable() {
		return getTable();
	}

	@Override
	public void setEntityPersisterClass(Class<? extends EntityPersister> persister) {
		this.entityPersisterClass = persister;
	}

	@Override
	public PersistentClass getSuperclass() {
		return null;
	}

	@Override
	public KeyValue getKey() {
		return getIdentifier();
	}

	public void setDiscriminator(Value discriminator) {
		this.discriminator = discriminator;
	}

	public void setEmbeddedIdentifier(boolean embeddedIdentifier) {
		this.embeddedIdentifier = embeddedIdentifier;
	}

	public void setExplicitPolymorphism(boolean explicitPolymorphism) {
		this.explicitPolymorphism = explicitPolymorphism;
	}

	public void setIdentifier(KeyValue identifier) {
		this.identifier = identifier;
	}

	public void setIdentifierProperty(Property identifierProperty) {
		this.identifierProperty = identifierProperty;
		identifierProperty.setPersistentClass( this );

	}

	public void setMutable(boolean mutable) {
		this.mutable = mutable;
	}

	@Override
	public boolean isDiscriminatorInsertable() {
		return discriminatorInsertable;
	}

	public void setDiscriminatorInsertable(boolean insertable) {
		this.discriminatorInsertable = insertable;
	}

	@Override
	public boolean isForceDiscriminator() {
		return forceDiscriminator;
	}

	public void setForceDiscriminator(boolean forceDiscriminator) {
		this.forceDiscriminator = forceDiscriminator;
	}

	@Override
	public String getWhere() {
		return where;
	}

	public void setWhere(String string) {
		where = string;
	}

	@Override
	public void validate(Metadata mapping) throws MappingException {
		super.validate( mapping );
		if ( !getIdentifier().isValid( mapping ) ) {
			throw new MappingException(
					"identifier mapping has wrong number of columns: " +
							getEntityName() +
							" type: " +
							getIdentifier().getType().getName()
			);
		}
		checkCompositeIdentifier();
	}

	private void checkCompositeIdentifier() {
		if ( getIdentifier() instanceof Component ) {
			Component id = (Component) getIdentifier();
			if ( !id.isDynamic() ) {
				final Class<?> idClass = id.getComponentClass();
				if ( idClass != null ) {
					final String idComponentClassName = idClass.getName();
					if ( !ReflectHelper.overridesEquals( idClass ) ) {
						LOG.compositeIdClassDoesNotOverrideEquals( idComponentClassName );
					}
					if ( !ReflectHelper.overridesHashCode( idClass ) ) {
						LOG.compositeIdClassDoesNotOverrideHashCode( idComponentClassName );
					}
				}
			}
		}
	}

	@Override
	public String getCacheConcurrencyStrategy() {
		return cacheConcurrencyStrategy;
	}

	public void setCacheConcurrencyStrategy(String cacheConcurrencyStrategy) {
		this.cacheConcurrencyStrategy = cacheConcurrencyStrategy;
	}

	public String getCacheRegionName() {
		return cacheRegionName == null ? getEntityName() : cacheRegionName;
	}

	public void setCacheRegionName(String cacheRegionName) {
		this.cacheRegionName = StringHelper.nullIfEmpty( cacheRegionName );
	}

	public boolean isLazyPropertiesCacheable() {
		return lazyPropertiesCacheable;
	}

	public void setLazyPropertiesCacheable(boolean lazyPropertiesCacheable) {
		this.lazyPropertiesCacheable = lazyPropertiesCacheable;
	}

	@Override
	public String getNaturalIdCacheRegionName() {
		return naturalIdCacheRegionName;
	}

	public void setNaturalIdCacheRegionName(String naturalIdCacheRegionName) {
		this.naturalIdCacheRegionName = naturalIdCacheRegionName;
	}

	@Override
	public boolean isJoinedSubclass() {
		return false;
	}

	@Override
	public Set<String> getSynchronizedTables() {
		return synchronizedTables;
	}

	public Set<Table> getIdentityTables() {
		Set<Table> tables = new HashSet<>();
		for ( PersistentClass clazz : getSubclassClosure() ) {
			if ( clazz.isAbstract() == null || !clazz.isAbstract() ) {
				tables.add( clazz.getIdentityTable() );
			}
		}
		return tables;
	}

	@Override
	public Object accept(PersistentClassVisitor mv) {
		return mv.accept( this );
	}

}
