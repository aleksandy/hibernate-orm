/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.orm.test.component.proxy;

import java.util.List;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.orm.test.jpa.BaseEntityManagerFunctionalTestCase;
import org.hibernate.type.ComponentType;

import org.hibernate.testing.TestForIssue;
import org.junit.Test;

import static org.hibernate.testing.transaction.TransactionUtil.doInJPA;
import static org.junit.Assert.assertEquals;

/**
 * @author Guillaume Smet
 * @author Oliver Libutzki
 */
public class ComponentBasicProxyTest extends BaseEntityManagerFunctionalTestCase {

	@Override
	protected Class<?>[] getAnnotatedClasses() {
		return new Class[]{
				Person.class, Adult.class
		};
	}

	@Test
	@TestForIssue(jiraKey = "HHH-12786")
	public void testBasicProxyingWithProtectedMethodCalledInConstructor() {
		doInJPA( this::entityManagerFactory, entityManager -> {
			Adult adult = new Adult();
			adult.setName( "Arjun Kumar" );
			entityManager.persist( adult );
		} );

		doInJPA( this::entityManagerFactory, entityManager -> {
			List<Adult> adultsCalledArjun = entityManager
					.createQuery( "SELECT a from Adult a WHERE a.name = :name", Adult.class )
					.setParameter( "name", "Arjun Kumar" ).getResultList();
			Adult adult = adultsCalledArjun.iterator().next();
			entityManager.remove( adult );
		} );
	}

	@Test
	@TestForIssue(jiraKey = "HHH-12791")
	public void testOnlyOneProxyClassGenerated() {
		StandardServiceRegistry ssr = new StandardServiceRegistryBuilder().build();

		try {
			Metadata metadata = new MetadataSources( ssr ).addAnnotatedClass( Person.class )
					.getMetadataBuilder()
					.build();
			PersistentClass persistentClass = metadata.getEntityBinding( Person.class.getName() );

			ComponentType componentType1 = (ComponentType) persistentClass.getIdentifierMapper().getType();
			Object instance1 = componentType1.instantiate();

			ComponentType componentType2 = (ComponentType) persistentClass.getIdentifierMapper().getType();
			Object instance2 = componentType2.instantiate();

			assertEquals( instance1.getClass(), instance2.getClass() );
		}
		finally {
			StandardServiceRegistryBuilder.destroy( ssr );
		}
	}
}
