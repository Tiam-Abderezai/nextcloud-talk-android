/*
 * Nextcloud Talk application
 *
 * @author Álvaro Brey
 * Copyright (C) 2022 Álvaro Brey
 * Copyright (C) 2022 Nextcloud GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.nextcloud.talk.dagger.modules

import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.polls.repositories.PollRepository
import com.nextcloud.talk.polls.repositories.PollRepositoryImpl
import com.nextcloud.talk.repositories.unifiedsearch.UnifiedSearchRepository
import com.nextcloud.talk.repositories.unifiedsearch.UnifiedSearchRepositoryImpl
import com.nextcloud.talk.shareditems.repositories.SharedItemsRepository
import com.nextcloud.talk.shareditems.repositories.SharedItemsRepositoryImpl
import com.nextcloud.talk.utils.database.user.CurrentUserProvider
import dagger.Module
import dagger.Provides

@Module
class RepositoryModule {
    @Provides
    fun provideSharedItemsRepository(ncApi: NcApi): SharedItemsRepository {
        return SharedItemsRepositoryImpl(ncApi)
    }

    @Provides
    fun provideUnifiedSearchRepository(ncApi: NcApi, userProvider: CurrentUserProvider): UnifiedSearchRepository {
        return UnifiedSearchRepositoryImpl(ncApi, userProvider)
    }

    @Provides
    fun provideDialogPollRepository(ncApi: NcApi, userProvider: CurrentUserProvider): PollRepository {
        return PollRepositoryImpl(ncApi, userProvider)
    }
}
