/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Specifies the <em>fetch group</em> for a persistent attribute of an entity
 * class. This annotation has no effect unless bytecode enhancement is used,
 * and field-level lazy fetching is enabled.
 * <ul>
 * <li>When bytecode enhancement is not used, declaring a field as
 *     {@link jakarta.persistence.Basic#fetch() @Basic(fetch=LAZY)} has no
 *     effect on the runtime behavior of Hibernate. All fields of an entity
 *     are loaded at the same time, as if they all belonged to the same fetch
 *     group.
 * <li>But when bytecode enhancement is used, a field declared
 *     {@code @Basic(fetch=LAZY)}} is loaded lazily when it is first accessed,
 *     using a separate SQL {@code select} statement. Since this trip to the
 *     database is generally expensive, Hibernate will, by default, load all
 *     lazy fields at once. This annotation provides control over that
 *     behavior.
 * </ul>
 * A fetch group identifies a set of related attributes that should be loaded
 * together when any one of them is accessed. By default, all non-collection
 * attributes belong to a single fetch group named {@code "DEFAULT"}. The
 * fetch group for a given lazy attribute may be explicitly specified using
 * the {@link #value()} member of this annotation.
 * <p>
 * For example, a field annotated {@code @Basic(fetch=LAZY) @LazyGroup("extra")}
 * belongs to the fetch group named {@code "extra"}, and is loaded whenever an
 * attribute belonging to the {@code "extra"} fetch group is accessed.
 * <p>
 * Note that field-level lazy fetching is usually of dubious value, and most
 * projects using Hibernate don't even bother enabling the bytecode enhancer.
 *
 * @author Steve Ebersole
 *
 * @see Cache#includeLazy()
 */
@java.lang.annotation.Target({ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface LazyGroup {
	String value();
}
