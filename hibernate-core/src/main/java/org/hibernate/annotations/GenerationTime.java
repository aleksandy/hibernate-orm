/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.annotations;

import org.hibernate.tuple.GenerationTiming;

/**
 * Represents the timing of value generation that occurs in the database.
 * Intended for use with the {@link Generated} annotation.
 *
 * @author Emmanuel Bernard
 *
 * @see Generated
 */
public enum GenerationTime {
	/**
	 * Indicates the value is never generated.
	 */
	NEVER( GenerationTiming.NEVER ),
	/**
	 * Indicates the value is generated on insert.
	 */
	INSERT( GenerationTiming.INSERT ),
	/**
	 * Indicates the value is generated on update.
	 */
	UPDATE( GenerationTiming.UPDATE ),
	/**
	 * Indicates the value is generated on insert and on update.
	 */
	ALWAYS( GenerationTiming.ALWAYS );

	private final GenerationTiming equivalent;

	private GenerationTime(GenerationTiming equivalent) {
		this.equivalent = equivalent;
	}

	public GenerationTiming getEquivalent() {
		return equivalent;
	}
}
