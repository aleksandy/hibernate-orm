/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.orm.test.bytecode.enhancement.lazy.proxy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hibernate.cfg.AvailableSettings.DEFAULT_LIST_SEMANTICS;
import static org.hibernate.testing.transaction.TransactionUtil.doInHibernate;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import org.hibernate.LazyInitializationException;
import org.hibernate.boot.internal.SessionFactoryBuilderImpl;
import org.hibernate.boot.internal.SessionFactoryOptionsBuilder;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.spi.SessionFactoryBuilderService;
import org.hibernate.cfg.Configuration;
import org.hibernate.metamodel.CollectionClassification;

import org.hibernate.testing.TestForIssue;
import org.hibernate.testing.bytecode.enhancement.BytecodeEnhancerRunner;
import org.hibernate.testing.bytecode.enhancement.EnhancementOptions;
import org.hibernate.testing.junit4.BaseCoreFunctionalTestCase;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@TestForIssue(jiraKey = "HHH-14811")
@RunWith(BytecodeEnhancerRunner.class)
@EnhancementOptions(lazyLoading = true)
public class BytecodeEnhancedLazyLoadingOnDeletedEntityTest
		extends BaseCoreFunctionalTestCase {

	@Override
	public Class<?>[] getAnnotatedClasses() {
		return new Class<?>[] { AssociationOwner.class, AssociationNonOwner.class };
	}

	@Override
	protected void configure(Configuration configuration) {
		super.configure( configuration );
		configuration.setProperty( DEFAULT_LIST_SEMANTICS, CollectionClassification.BAG.name() );
	}

	@Override
	protected void prepareBasicRegistryBuilder(StandardServiceRegistryBuilder serviceRegistryBuilder) {
		serviceRegistryBuilder.addService(
				SessionFactoryBuilderService.class,
				(SessionFactoryBuilderService) (metadata, bootstrapContext) -> {
					SessionFactoryOptionsBuilder optionsBuilder = new SessionFactoryOptionsBuilder(
							metadata.getMetadataBuildingOptions().getServiceRegistry(),
							bootstrapContext
					);
					// This test only makes sense if association properties *can* be uninitialized.
					optionsBuilder.enableCollectionInDefaultFetchGroup( false );
					return new SessionFactoryBuilderImpl( metadata, optionsBuilder );
				}
		);
	}

	@After
	public void tearDown() {
		doInHibernate( this::sessionFactory, s -> {
			s.createQuery( "delete from AOwner" ).executeUpdate();
			s.createQuery( "delete from ANonOwner" ).executeUpdate();
		} );
	}

	@Test
	public void accessUnloadedLazyAssociationOnDeletedOwner() {
		inTransaction( s -> {
			AssociationOwner owner = new AssociationOwner();
			owner.setId( 1 );
			for ( int i = 0; i < 2; i++ ) {
				AssociationNonOwner nonOwner = new AssociationNonOwner();
				nonOwner.setId( i );
				s.persist( nonOwner );
				nonOwner.getOwners().add( owner );
				owner.getNonOwners().add( nonOwner );
			}
			s.persist( owner );
		} );
		assertThatThrownBy( () -> inTransaction( session -> {
			AssociationOwner owner = session.load( AssociationOwner.class, 1 );
			session.delete( owner );
			session.flush();
			owner.getNonOwners().size();
		} ) )
				.isInstanceOf( LazyInitializationException.class )
				.hasMessageContaining(
						"Could not locate EntityEntry for the collection owner in the PersistenceContext" );
	}

	@Test
	public void accessUnloadedLazyAssociationOnDeletedNonOwner() {
		inTransaction( s -> {
			AssociationNonOwner nonOwner = new AssociationNonOwner();
			nonOwner.setId( 1 );
			s.persist( nonOwner );
		} );
		assertThatThrownBy( () -> inTransaction( session -> {
			AssociationNonOwner nonOwner = session.load( AssociationNonOwner.class, 1 );
			session.delete( nonOwner );
			session.flush();
			nonOwner.getOwners().size();
		} ) )
				.isInstanceOf( LazyInitializationException.class )
				.hasMessageContaining(
						"Could not locate EntityEntry for the collection owner in the PersistenceContext" );
	}

	@Entity(name = "AOwner")
	@Table
	private static class AssociationOwner {

		@Id
		Integer id;

		@ManyToMany(fetch = FetchType.LAZY)
		List<AssociationNonOwner> nonOwners = new ArrayList<>();

		public Integer getId() {
			return id;
		}

		public void setId(Integer id) {
			this.id = id;
		}

		public List<AssociationNonOwner> getNonOwners() {
			return nonOwners;
		}

		public void setNonOwners(
				List<AssociationNonOwner> nonOwners) {
			this.nonOwners = nonOwners;
		}
	}

	@Entity(name = "ANonOwner")
	@Table
	private static class AssociationNonOwner {

		@Id
		Integer id;

		@ManyToMany(mappedBy = "nonOwners", fetch = FetchType.LAZY)
		List<AssociationOwner> owners = new ArrayList<>();

		AssociationNonOwner() {
		}

		public Integer getId() {
			return id;
		}

		public void setId(Integer id) {
			this.id = id;
		}

		public List<AssociationOwner> getOwners() {
			return owners;
		}

		public void setOwners(List<AssociationOwner> owners) {
			this.owners = owners;
		}
	}
}
