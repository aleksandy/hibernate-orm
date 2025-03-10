/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import org.hibernate.bytecode.enhance.spi.interceptor.BytecodeLazyAttributeInterceptor;
import org.hibernate.bytecode.enhance.spi.interceptor.EnhancementAsProxyLazinessInterceptor;
import org.hibernate.collection.spi.PersistentBag;
import org.hibernate.collection.spi.PersistentList;
import org.hibernate.collection.spi.PersistentMap;
import org.hibernate.collection.spi.PersistentSet;
import org.hibernate.collection.spi.PersistentSortedMap;
import org.hibernate.collection.spi.PersistentSortedSet;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.PersistentAttributeInterceptor;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.hibernate.collection.spi.LazyInitializable;

import static org.hibernate.engine.internal.ManagedTypeHelper.asPersistentAttributeInterceptable;
import static org.hibernate.engine.internal.ManagedTypeHelper.isPersistentAttributeInterceptable;

/**
 * Various utility functions for working with proxies and lazy collection references.
 * <p>
 * Operations like {@link #isInitialized(Object)} and {@link #initialize(Object)} are
 * of general purpose. But {@link #createDetachedProxy(SessionFactory, Class, Object)}
 * and {@link CollectionInterface#createDetachedProxy(SessionFactory, Class, Object)}
 * are intended for use by generic code that must materialize an "amputated" graph of
 * Hibernate entities. (For example, a library which deserializes entities from JSON.)
 * <p>
 * Graphs of Hibernate entities obtained from a {@link Session} are usually in an
 * amputated form, with associations and collections replaced by proxies and lazy
 * collections. (That is, by instances of the internal types {@link HibernateProxy}
 * and {@link PersistentCollection}.) These objects are fully serializable using
 * Java serialization, but can cause discomfort when working with custom serialization
 * libraries. Therefore, this class defines operations that may be used to write code
 * that completely removes the amputated leaves of the graph (the proxies) during
 * serialization, and rematerializes and reattaches them during deserialization. It's
 * possible, in principle, to use these operations, together with reflection, or with
 * the Hibernate metamodel, to write such generic code for any given serialization
 * library, but the details depend on what facilities the library itself offers for
 * the program to intervene in the process of serialization and of deserialization.
 *
 * @author Gavin King
 */
public final class Hibernate {
	/**
	 * Cannot be instantiated.
	 */
	private Hibernate() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Force initialization of a proxy or persistent collection. In the case of a
	 * many-valued association, only the collection itself is initialized. It is not
	 * guaranteed that the associated entities held within the collection will be
	 * initialized.
	 *
	 * @param proxy a persistable object, proxy, persistent collection or {@code null}
	 * @throws HibernateException if the proxy cannot be initialized at this time,
	 * for example, if the {@code Session} was closed
	 */
	public static void initialize(Object proxy) throws HibernateException {
		if ( proxy == null ) {
			return;
		}

		if ( proxy instanceof HibernateProxy ) {
			( (HibernateProxy) proxy ).getHibernateLazyInitializer().initialize();
		}
		else if ( proxy instanceof LazyInitializable ) {
			( (LazyInitializable) proxy ).forceInitialization();
		}
		else if ( isPersistentAttributeInterceptable( proxy ) ) {
			final PersistentAttributeInterceptor interceptor = asPersistentAttributeInterceptable( proxy ).$$_hibernate_getInterceptor();
			if ( interceptor instanceof EnhancementAsProxyLazinessInterceptor ) {
				( (EnhancementAsProxyLazinessInterceptor) interceptor ).forceInitialize( proxy, null );
			}
		}
	}

	/**
	 * Determines if the given proxy or persistent collection is initialized.
	 * <p>
	 * This operation is equivalent to {@link jakarta.persistence.PersistenceUtil#isLoaded(Object)}.
	 *
	 * @param proxy a persistable object, proxy, persistent collection or {@code null}
	 * @return true if the argument is already initialized, or is not a proxy or collection
	 */
	public static boolean isInitialized(Object proxy) {
		if ( proxy instanceof HibernateProxy ) {
			return !( (HibernateProxy) proxy ).getHibernateLazyInitializer().isUninitialized();
		}
		else if ( isPersistentAttributeInterceptable( proxy ) ) {
			final PersistentAttributeInterceptor interceptor = asPersistentAttributeInterceptable( proxy ).$$_hibernate_getInterceptor();
			return !(interceptor instanceof EnhancementAsProxyLazinessInterceptor);
		}
		else if ( proxy instanceof LazyInitializable ) {
			return ( (LazyInitializable) proxy ).wasInitialized();
		}
		else {
			return true;
		}
	}

	/**
	 * Obtain the {@linkplain Collection#size() size} of a persistent collection,
	 * without fetching its state from the database.
	 *
	 * @param collection a persistent collection associated with an open session
	 * @return the size of the collection
	 *
	 * @since 6.1.1
	 */
	public static int size(Collection<?> collection) {
		return collection instanceof PersistentCollection
				? ((PersistentCollection<?>) collection).getSize()
				: collection.size();
	}

	/**
	 * Determine if the given persistent collection {@linkplain Collection#contains(Object) contains}
	 * the given element, without fetching its state from the database.
	 *
	 * @param collection a persistent collection associated with an open session
	 * @return true if the collection does contain the given element
	 *
	 * @since 6.1.1
	 */
	public static <T> boolean contains(Collection<? super T> collection, T element) {
		return collection instanceof PersistentCollection
				? ((PersistentCollection<?>) collection).elementExists(element)
				: collection.contains(element);
	}

	/**
	 * Obtain the value associated with the given key by the given persistent
	 * map, without fetching the state of the map from the database.
	 *
	 * @param map a persistent map associated with an open session
	 * @param key a key belonging to the map
	 * @return the value associated by the map with the given key
	 *
	 * @since 6.1.1
	 */
	public static <K,V> V get(Map<? super K, V> map, K key) {
		return map instanceof PersistentCollection
				? (V) ((PersistentCollection<?>) map).elementByIndex(key)
				: map.get(key);
	}

	/**
	 * Obtain the element of the given persistent list with the given index,
	 * without fetching the state of the list from the database.
	 *
	 * @param list a persistent list associated with an open session
	 * @param key an index belonging to the list
	 * @return the element of the list with the given index
	 *
	 * @since 6.1.1
	 */
	public static <T> T get(List<T> list, int key) {
		return list instanceof PersistentCollection
				? (T) ((PersistentCollection<?>) list).elementByIndex(key)
				: list.get(key);
	}

	/**
	 * Get the true, underlying class of a proxied persistent class. This operation
	 * will initialize a proxy by side effect.
	 *
	 * @param proxy an entity instance or proxy
	 * @return the true class of the instance
	 */
	@SuppressWarnings("unchecked")
	public static <T> Class<? extends T> getClass(T proxy) {
		Class<?> result;
		if ( proxy instanceof HibernateProxy ) {
			result = ( (HibernateProxy) proxy ).getHibernateLazyInitializer()
					.getImplementation()
					.getClass();
		}
		else {
			result = proxy.getClass();
		}
		return (Class<? extends T>) result;
	}

	/**
	 * Determines if the property with the given name of the given entity instance is
	 * initialized. If the named property does not exist or is not persistent, this
	 * method always returns {@code true}.
	 * <p>
	 * This operation is equivalent to {@link jakarta.persistence.PersistenceUtil#isLoaded(Object, String)}.
	 *
	 * @param proxy The potential proxy
	 * @param propertyName the name of a persistent attribute of the object
	 * @return true if the named property of the object is not listed as uninitialized;
	 *         false otherwise
	 */
	public static boolean isPropertyInitialized(Object proxy, String propertyName) {
		final Object entity;
		if ( proxy instanceof HibernateProxy ) {
			final LazyInitializer li = ( (HibernateProxy) proxy ).getHibernateLazyInitializer();
			if ( li.isUninitialized() ) {
				return false;
			}
			else {
				entity = li.getImplementation();
			}
		}
		else {
			entity = proxy;
		}

		if ( isPersistentAttributeInterceptable( entity ) ) {
			PersistentAttributeInterceptor interceptor = asPersistentAttributeInterceptable( entity ).$$_hibernate_getInterceptor();
			if ( interceptor instanceof BytecodeLazyAttributeInterceptor ) {
				return ( (BytecodeLazyAttributeInterceptor) interceptor ).isAttributeLoaded( propertyName );
			}
		}

		return true;
	}

    /**
     * If the given object is not a proxy, return it. But, if it is a proxy, ensure
	 * that the proxy is initialized, and return a direct reference to its proxied
	 * entity object.
	 *
	 * @param proxy an object which might be a proxy for an entity
	 * @return a reference that is never proxied
	 *
	 * @throws LazyInitializationException if this operation is called on an
	 * uninitialized proxy that is not associated with an open session.
     */
	public static Object unproxy(Object proxy) {
		if ( proxy instanceof HibernateProxy ) {
			HibernateProxy hibernateProxy = (HibernateProxy) proxy;
			LazyInitializer initializer = hibernateProxy.getHibernateLazyInitializer();
			return initializer.getImplementation();
		}
		else {
			return proxy;
		}
	}

	/**
	 * If the given object is not a proxy, cast it to the given type, and return it.
	 * But, if it is a proxy, ensure that the proxy is initialized, and return a
	 * direct reference to its proxied entity object, after casting to the given type.
	 *
	 * @param proxy an object which might be a proxy for an entity
	 * @param entityClass an entity type to cast to
	 * @return a reference that is never proxied
	 *
	 * @throws LazyInitializationException if this operation is called on an
	 * uninitialized proxy that is not associated with an open session.
	 */
	public static <T> T unproxy(T proxy, Class<T> entityClass) {
		return entityClass.cast( unproxy( proxy ) );
	}

	/**
	 * Obtain a detached, uninitialized reference (a proxy) for a persistent entity with
	 * the given identifier.
	 *
	 * The returned proxy is not associated with any session, and cannot be initialized
	 * by calling {@link #initialize(Object)}. It can be used to represent a reference to
	 * the entity when working with a detached object graph.
	 *
	 * @param sessionFactory the session factory with which the entity is associated
	 * @param entityClass the entity class
	 * @param id the id of the persistent entity instance
	 *
	 * @return a detached uninitialized proxy
	 *
	 * @since 6.0
	 */
	@SuppressWarnings("unchecked")
	public static <E> E createDetachedProxy(SessionFactory sessionFactory, Class<E> entityClass, Object id) {
		final EntityPersister persister = sessionFactory.unwrap(SessionFactoryImplementor.class)
				.getRuntimeMetamodels()
				.getMappingMetamodel()
				.findEntityDescriptor(entityClass);
		if (persister==null) {
			throw new UnknownEntityTypeException("unknown entity type");
		}
		return (E) persister.createProxy(id, null);
	}

	/**
	 * Operations for obtaining references to persistent collections of a certain type.
	 *
	 * @param <C> the type of collection, for example, {@code List&lt;User&gt;}
	 *
	 * @since 6.0
	 */
	public static final class CollectionInterface<C> {
		private final Supplier<C> detached;
		private final Supplier<C> created;

		private CollectionInterface(Supplier<C> detached, Supplier<C> created) {
			this.detached = detached;
			this.created = created;
		}

		/**
		 * Obtain a detached, uninitialized persistent collection of the type represented
		 * by this object.
		 *
		 * The returned wrapper object is not associated with any session, and cannot be
		 * initialized by calling {@link #initialize(Object)}. It can be used to represent
		 * an uninitialized collection when working with a detached object graph.
		 *
		 * @return an uninitialized persistent collection
		 */
		public C createDetachedInstance() {
			return detached.get();
		}

		/**
		 * Instantiate an empty collection of the type represented by this object.
		 *
		 * @return a newly-instantiated empty collection
		 */
		public C createNewInstance() {
			return created.get();
		}
	}

	/**
	 * Obtain an instance of {@link CollectionInterface} representing persistent bags
	 * of a given element type.
	 *
	 * @param <U> the element type
	 *
	 * @since 6.0
	 */
	public static <U> CollectionInterface<Collection<U>> bag() {
		return new CollectionInterface<>(PersistentBag::new, ArrayList::new);
	}

	/**
	 * Obtain an instance of {@link CollectionInterface} representing persistent sets
	 * of a given element type.
	 *
	 * @param <U> the element type
	 *
	 * @since 6.0
	 */
	public static <U> CollectionInterface<Set<U>> set() {
		return new CollectionInterface<>(PersistentSet::new, HashSet::new);
	}

	/**
	 * Obtain an instance of {@link CollectionInterface} representing persistent lists
	 * of a given element type.
	 *
	 * @param <U> the element type
	 *
	 * @since 6.0
	 */
	public static <U> CollectionInterface<List<U>> list() {
		return new CollectionInterface<>(PersistentList::new, ArrayList::new);
	}

	/**
	 * Obtain an instance of {@link CollectionInterface} representing persistent maps
	 * of a given key and value types.
	 *
	 * @param <U> the key type
	 * @param <V> the value type
	 *
	 * @since 6.0
	 */
	public static <U,V> CollectionInterface<Map<U,V>> map() {
		return new CollectionInterface<>(PersistentMap::new, HashMap::new);
	}

	/**
	 * Obtain an instance of {@link CollectionInterface} representing sorted persistent sets
	 * of a given element type.
	 *
	 * @param <U> the element type
	 *
	 * @since 6.0
	 */
	public static <U> CollectionInterface<SortedSet<U>> sortedSet() {
		return new CollectionInterface<>(PersistentSortedSet::new, TreeSet::new);
	}

	/**
	 * Obtain an instance of {@link CollectionInterface} representing sorted persistent maps
	 * of a given key and value types.
	 *
	 * @param <U> the key type
	 * @param <V> the value type
	 *
	 * @since 6.0
	 */
	public static <U,V> CollectionInterface<Map<U,V>> sortedMap() {
		return new CollectionInterface<>(PersistentSortedMap::new, TreeMap::new);
	}

	/**
	 * Obtain an instance of {@link CollectionInterface} representing persistent collections
	 * of the given type.
	 *
	 * @param collectionClass the Java class object representing the collection type
	 *
	 * @since 6.0
	 */
	@SuppressWarnings("unchecked")
	public static <C> CollectionInterface<C> collection(Class<C> collectionClass) {
		if (collectionClass == List.class) {
			return (CollectionInterface<C>) list();
		}
		else if (collectionClass == Set.class) {
			return (CollectionInterface<C>) set();
		}
		else if (collectionClass == Map.class) {
			return (CollectionInterface<C>) map();
		}
		if (collectionClass == SortedMap.class) {
			return (CollectionInterface<C>) sortedMap();
		}
		else if (collectionClass == SortedSet.class) {
			return (CollectionInterface<C>) sortedSet();
		}
		else if (collectionClass == Collection.class) {
			return (CollectionInterface<C>) bag();
		}
		else {
			throw new IllegalArgumentException("illegal collection interface type");
		}
	}
}
