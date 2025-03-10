/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.type;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import org.hibernate.HibernateException;
import org.hibernate.Internal;
import org.hibernate.MappingException;
import org.hibernate.engine.spi.Mapping;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

/**
 * Defines a mapping between a Java type and one or more JDBC {@linkplain java.sql.Types types}, as well
 * as describing the in-memory semantics of the given java type (how do we check it for 'dirtiness', how do
 * we copy values, etc).
 * <p/>
 * Application developers needing custom types can implement this interface (either directly or via subclassing an
 * existing impl) or by the (slightly more stable, though more limited) {@link org.hibernate.usertype.UserType}
 * interface.
 * <p/>
 * Implementations of this interface must certainly be thread-safe.  It is recommended that they be immutable as
 * well, though that is difficult to achieve completely given the no-arg constructor requirement for custom types.
 *
 * @author Gavin King
 * @author Steve Ebersole
 */
@Internal
public interface Type extends Serializable {
	/**
	 * Return true if the implementation is castable to {@link AssociationType}. This does not necessarily imply that
	 * the type actually represents an association.  Essentially a polymorphic version of
	 * {@code (type instanceof AssociationType.class)}
	 *
	 * @return True if this type is also an {@link AssociationType} implementor; false otherwise.
	 */
	boolean isAssociationType();

	/**
	 * Return true if the implementation is castable to {@link CollectionType}. Essentially a polymorphic version of
	 * {@code (type instanceof CollectionType.class)}
	 * <p/>
	 * A {@link CollectionType} is additionally an {@link AssociationType}; so if this method returns true,
	 * {@link #isAssociationType()} should also return true.
	 *
	 * @return True if this type is also a {@link CollectionType} implementor; false otherwise.
	 */
	boolean isCollectionType();

	/**
	 * Return true if the implementation is castable to {@link EntityType}. Essentially a polymorphic
	 * version of {@code (type instanceof EntityType.class)}.
	 * <p/>
	 * An {@link EntityType} is additionally an {@link AssociationType}; so if this method returns true,
	 * {@link #isAssociationType()} should also return true.
	 *
	 * @return True if this type is also an {@link EntityType} implementor; false otherwise.
	 */
	boolean isEntityType();

	/**
	 * Return true if the implementation is castable to {@link AnyType}. Essentially a polymorphic
	 * version of {@code (type instanceof AnyType.class)}.
	 * <p/>
	 * An {@link AnyType} is additionally an {@link AssociationType}; so if this method returns true,
	 * {@link #isAssociationType()} should also return true.
	 *
	 * @return True if this type is also an {@link AnyType} implementor; false otherwise.
	 */
	boolean isAnyType();

	/**
	 * Return true if the implementation is castable to {@link CompositeType}. Essentially a polymorphic
	 * version of {@code (type instanceof CompositeType.class)}.  A component type may own collections or
	 * associations and hence must provide certain extra functionality.
	 *
	 * @return True if this type is also a {@link CompositeType} implementor; false otherwise.
	 */
	boolean isComponentType();

	/**
	 * How many columns are used to persist this type.  Always the same as {@code sqlTypes(mapping).length}
	 *
	 * @param mapping The mapping object :/
	 *
	 * @return The number of columns
	 *
	 * @throws MappingException Generally indicates an issue accessing the passed mapping object.
	 */
	int getColumnSpan(Mapping mapping) throws MappingException;

	/**
	 * Return the JDBC types codes (per {@link java.sql.Types}) for the columns mapped by this type.
	 * <p/>
	 * NOTE: The number of elements in this array matches the return from {@link #getColumnSpan}.
	 *
	 * @param mapping The mapping object :/
	 *
	 * @return The JDBC type codes.
	 *
	 * @throws MappingException Generally indicates an issue accessing the passed mapping object.
	 */
	int[] getSqlTypeCodes(Mapping mapping) throws MappingException;

	/**
	 * The class handled by this type.
	 *
	 * @return The java type class handled by this type.
	 */
	Class<?> getReturnedClass();

	/**
	 * Compare two instances of the class mapped by this type for persistence "equality" (equality of persistent
	 * state) taking a shortcut for entity references.
	 * <p/>
	 * For most types this should equate to an {@link Object#equals equals} check on the values.  For associations
	 * the implication is a bit different.  For most types it is conceivable to simply delegate to {@link #isEqual}
	 *
	 * @param x The first value
	 * @param y The second value
	 *
	 * @return True if there are considered the same (see discussion above).
	 *
	 * @throws HibernateException A problem occurred performing the comparison
	 */
	boolean isSame(Object x, Object y) throws HibernateException;

	/**
	 * Compare two instances of the class mapped by this type for persistence "equality" (equality of persistent
	 * state).
	 * <p/>
	 * This should always equate to some form of comparison of the value's internal state.  As an example, for
	 * something like a date the comparison should be based on its internal "time" state based on the specific portion
	 * it is meant to represent (timestamp, date, time).
	 *
	 * @param x The first value
	 * @param y The second value
	 *
	 * @return True if there are considered equal (see discussion above).
	 *
	 * @throws HibernateException A problem occurred performing the comparison
	 */
	boolean isEqual(Object x, Object y) throws HibernateException;

	/**
	 * Compare two instances of the class mapped by this type for persistence "equality" (equality of persistent
	 * state).
	 * <p/>
	 * This should always equate to some form of comparison of the value's internal state.  As an example, for
	 * something like a date the comparison should be based on its internal "time" state based on the specific portion
	 * it is meant to represent (timestamp, date, time).
	 *
	 * @param x The first value
	 * @param y The second value
	 * @param factory The session factory
	 *
	 * @return True if there are considered equal (see discussion above).
	 *
	 * @throws HibernateException A problem occurred performing the comparison
	 */
	boolean isEqual(Object x, Object y, SessionFactoryImplementor factory) throws HibernateException;

	/**
	 * Get a hash code, consistent with persistence "equality".  Again for most types the normal usage is to
	 * delegate to the value's {@link Object#hashCode hashCode}.
	 *
	 * @param x The value for which to retrieve a hash code
	 * @return The hash code
	 *
	 * @throws HibernateException A problem occurred calculating the hash code
	 */
	int getHashCode(Object x) throws HibernateException;

	/**
	 * Get a hash code, consistent with persistence "equality".  Again for most types the normal usage is to
	 * delegate to the value's {@link Object#hashCode hashCode}.
	 *
	 * @param x The value for which to retrieve a hash code
	 * @param factory The session factory
	 *
	 * @return The hash code
	 *
	 * @throws HibernateException A problem occurred calculating the hash code
	 */
	int getHashCode(Object x, SessionFactoryImplementor factory) throws HibernateException;

	/**
	 * Perform a {@link java.util.Comparator} style comparison between values
	 *
	 * @param x The first value
	 * @param y The second value
	 *
	 * @return The comparison result.  See {@link java.util.Comparator#compare} for a discussion.
	 */
	int compare(Object x, Object y);

	/**
	 * Should the parent be considered dirty, given both the old and current value?
	 *
	 * @param old the old value
	 * @param current the current value
	 * @param session The session from which the request originated.
	 *
	 * @return true if the field is dirty
	 *
	 * @throws HibernateException A problem occurred performing the checking
	 */
	boolean isDirty(Object old, Object current, SharedSessionContractImplementor session) throws HibernateException;

	/**
	 * Should the parent be considered dirty, given both the old and current value?
	 *
	 * @param oldState the old value
	 * @param currentState the current value
	 * @param checkable An array of booleans indicating which columns making up the value are actually checkable
	 * @param session The session from which the request originated.
	 *
	 * @return true if the field is dirty
	 *
	 * @throws HibernateException A problem occurred performing the checking
	 */
	boolean isDirty(Object oldState, Object currentState, boolean[] checkable, SharedSessionContractImplementor session)
			throws HibernateException;

	/**
	 * Has the value been modified compared to the current database state?  The difference between this
	 * and the {@link #isDirty} methods is that here we need to account for "partially" built values.  This is really
	 * only an issue with association types.  For most type implementations it is enough to simply delegate to
	 * {@link #isDirty} here/
	 *
	 * @param dbState the database state, in a "hydrated" form, with identifiers unresolved
	 * @param currentState the current state of the object
	 * @param checkable which columns are actually checkable
	 * @param session The session from which the request originated.
	 *
	 * @return true if the field has been modified
	 *
	 * @throws HibernateException A problem occurred performing the checking
	 */
	boolean isModified(
			Object dbState,
			Object currentState,
			boolean[] checkable,
			SharedSessionContractImplementor session)
			throws HibernateException;

	/**
	 * Bind a value represented by an instance of the {@link #getReturnedClass() mapped class} to the JDBC prepared
	 * statement, ignoring some columns as dictated by the 'settable' parameter.  Implementors should handle the
	 * possibility of null values.  A multi-column type should bind parameters starting from {@code index}.
	 *
	 * @param st The JDBC prepared statement to which to bind
	 * @param value the object to write
	 * @param index starting parameter bind index
	 * @param settable an array indicating which columns to bind/ignore
	 * @param session The originating session
	 *
	 * @throws HibernateException An error from Hibernate
	 * @throws SQLException An error from the JDBC driver
	 */
	void nullSafeSet(
			PreparedStatement st,
			Object value,
			int index,
			boolean[] settable,
			SharedSessionContractImplementor session)
	throws HibernateException, SQLException;

	/**
	 * Bind a value represented by an instance of the {@link #getReturnedClass() mapped class} to the JDBC prepared
	 * statement.  Implementors should handle possibility of null values.  A multi-column type should bind parameters
	 * starting from {@code index}.
	 *
	 * @param st The JDBC prepared statement to which to bind
	 * @param value the object to write
	 * @param index starting parameter bind index
	 * @param session The originating session
	 *
	 * @throws HibernateException An error from Hibernate
	 * @throws SQLException An error from the JDBC driver
	 */
	void nullSafeSet(PreparedStatement st, Object value, int index, SharedSessionContractImplementor session)
	throws HibernateException, SQLException;

	/**
	 * Generate a representation of the value for logging purposes.
	 *
	 * @param value The value to be logged
	 * @param factory The session factory
	 *
	 * @return The loggable representation
	 *
	 * @throws HibernateException An error from Hibernate
	 */
	String toLoggableString(Object value, SessionFactoryImplementor factory)
	throws HibernateException;

	/**
	 * Returns the abbreviated name of the type.
	 *
	 * @return String the Hibernate type name
	 */
	String getName();

	/**
	 * Return a deep copy of the persistent state, stopping at entities and at collections.
	 *
	 * @param value The value to be copied
	 * @param factory The session factory
	 *
	 * @return The deep copy
	 *
	 * @throws HibernateException An error from Hibernate
	 */
	Object deepCopy(Object value, SessionFactoryImplementor factory)
	throws HibernateException;

	/**
	 * Are objects of this type mutable. (With respect to the referencing object ...
	 * entities and collections are considered immutable because they manage their
	 * own internal state.)
	 *
	 * @return boolean
	 */
	boolean isMutable();

	/**
	 * Return a disassembled representation of the object.  This is the value Hibernate will use in as cache key,
	 * so care should be taken to break values down to their simplest forms; for entities especially, this
	 * means breaking them down into their constituent parts.
	 *
	 * For two disassembled objects A and B, {@link Object#equals(Object)} must behave like {@link #isEqual(Object, Object)}.
	 *
	 * @param value the value to cache
	 * @param sessionFactory the session factory
	 *
	 * @return the disassembled, deep cloned state
	 *
	 * @throws HibernateException An error from Hibernate
	 */
	default Serializable disassemble(Object value, SessionFactoryImplementor sessionFactory) throws HibernateException {
		return disassemble( value, null, null );
	}

	/**
	 * Return a disassembled representation of the object.  This is the value Hibernate will use in second level
	 * caching, so care should be taken to break values down to their simplest forms; for entities especially, this
	 * means breaking them down into their constituent parts.
	 *
	 * @param value the value to cache
	 * @param session the originating session
	 * @param owner optional parent entity object (needed for collections)
	 *
	 * @return the disassembled, deep cloned state
	 *
	 * @throws HibernateException An error from Hibernate
	 */
	Serializable disassemble(Object value, SharedSessionContractImplementor session, Object owner) throws HibernateException;

	/**
	 * Reconstruct the object from its disassembled state.  This method is the reciprocal of {@link #disassemble(Object, SharedSessionContractImplementor, Object)}
	 *
	 * @param cached the disassembled state from the cache
	 * @param session the originating session
	 * @param owner the parent entity object
	 *
	 * @return the (re)assembled object
	 *
	 * @throws HibernateException An error from Hibernate
	 */
	Object assemble(Serializable cached, SharedSessionContractImplementor session, Object owner) throws HibernateException;

	/**
	 * Called before assembling a query result set from the query cache, to allow batch fetching
	 * of entities missing from the second-level cache.
	 *
	 * @param cached The key
	 * @param session The originating session
	 */
	void beforeAssemble(Serializable cached, SharedSessionContractImplementor session);

	/**
	 * During merge, replace the existing (target) value in the entity we are merging to
	 * with a new (original) value from the detached entity we are merging. For immutable
	 * objects, or null values, it is safe to simply return the first parameter. For
	 * mutable objects, it is safe to return a copy of the first parameter. For objects
	 * with component values, it might make sense to recursively replace component values.
	 *
	 * @param original the value from the detached entity being merged
	 * @param target the value in the managed entity
	 * @param session The originating session
	 * @param owner The owner of the value
	 * @param copyCache The cache of already copied/replaced values
	 *
	 * @return the value to be merged
	 *
	 * @throws HibernateException An error from Hibernate
	 */
	Object replace(
			Object original,
			Object target,
			SharedSessionContractImplementor session,
			Object owner,
			Map<Object, Object> copyCache) throws HibernateException;

	/**
	 * During merge, replace the existing (target) value in the entity we are merging to
	 * with a new (original) value from the detached entity we are merging. For immutable
	 * objects, or null values, it is safe to simply return the first parameter. For
	 * mutable objects, it is safe to return a copy of the first parameter. For objects
	 * with component values, it might make sense to recursively replace component values.
	 *
	 * @param original the value from the detached entity being merged
	 * @param target the value in the managed entity
	 * @param session The originating session
	 * @param owner The owner of the value
	 * @param copyCache The cache of already copied/replaced values
	 * @param foreignKeyDirection For associations, which direction does the foreign key point?
	 *
	 * @return the value to be merged
	 *
	 * @throws HibernateException An error from Hibernate
	 */
	Object replace(
			Object original,
			Object target,
			SharedSessionContractImplementor session,
			Object owner,
			Map<Object, Object> copyCache,
			ForeignKeyDirection foreignKeyDirection) throws HibernateException;

	/**
	 * Given an instance of the type, return an array of boolean, indicating
	 * which mapped columns would be null.
	 *
	 * @param value an instance of the type
	 * @param mapping The mapping abstraction
	 *
	 * @return array indicating column nullness for a value instance
	 */
	boolean[] toColumnNullness(Object value, Mapping mapping);

}
