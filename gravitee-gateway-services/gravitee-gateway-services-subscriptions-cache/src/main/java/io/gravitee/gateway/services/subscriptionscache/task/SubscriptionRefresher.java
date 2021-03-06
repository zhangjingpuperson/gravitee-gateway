/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.gateway.services.subscriptionscache.task;

import io.gravitee.gateway.handlers.api.definition.Api;
import io.gravitee.gateway.handlers.api.definition.Plan;
import io.gravitee.repository.exceptions.TechnicalException;
import io.gravitee.repository.management.api.SubscriptionRepository;
import io.gravitee.repository.management.api.search.SubscriptionCriteria;
import io.gravitee.repository.management.model.Subscription;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class SubscriptionRefresher implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionRefresher.class);

    private static final int TIMEFRAME_BEFORE_DELAY = 10 * 60 * 1000;
    private static final int TIMEFRAME_AFTER_DELAY = 1 * 60 * 1000;

    private SubscriptionRepository subscriptionRepository;

    private Ehcache cache;

    private final Api api;

    private Collection<String> plans;

    private long lastRefreshAt = -1;

    public SubscriptionRefresher(final Api api) {
        this.api = api;
    }

    public void initialize() {
        this.plans = api.getPlans()
                .stream()
                .filter(plan -> io.gravitee.repository.management.model.Plan.PlanSecurityType.OAUTH2.name()
                        .equalsIgnoreCase(plan.getSecurity()) ||
                                io.gravitee.repository.management.model.Plan.PlanSecurityType.JWT.name()
                                        .equalsIgnoreCase(plan.getSecurity()))
                .map(Plan::getId)
                .collect(Collectors.toList());
    }

    @Override
    public void run() {
        if (! plans.isEmpty()) {
            long nextLastRefreshAt = System.currentTimeMillis();
            LOGGER.debug("Refresh subscriptions for API id[{}] name[{}]", api.getId(), api.getName());

            final SubscriptionCriteria.Builder criteriaBuilder = new SubscriptionCriteria.Builder()
                    .plans(plans);

            if (lastRefreshAt == -1) {
                criteriaBuilder.status(Subscription.Status.ACCEPTED);
            } else {
                criteriaBuilder
                        .statuses(Arrays.asList(Subscription.Status.ACCEPTED, Subscription.Status.CLOSED))
                        .from(lastRefreshAt - TIMEFRAME_BEFORE_DELAY)
                        .to(nextLastRefreshAt + TIMEFRAME_AFTER_DELAY);
            }

            try {
                subscriptionRepository
                        .search(criteriaBuilder.build())
                        .forEach(this::saveOrUpdate);

                lastRefreshAt = nextLastRefreshAt;
            } catch (TechnicalException te) {
                LOGGER.error("Unexpected error while refreshing subscriptions", te);
            }
        }
    }

    private void saveOrUpdate(Subscription subscription) {
        String key = subscription.getPlan() + '-' + subscription.getClientId();

        Element element = cache.get(subscription.getId());

        if (subscription.getStatus() == Subscription.Status.CLOSED && element != null) {
            cache.removeElement(element);
            String oldKey = (String) element.getObjectValue();
            Element eltSubscription = cache.get(oldKey);
            if (eltSubscription != null && ((Subscription)eltSubscription.getObjectValue()).getId().equals(subscription.getId())) {
                cache.remove(oldKey);
            }
        } else {
            LOGGER.debug("Cache a subscription: plan[{}] application[{}] client_id[{}]", subscription.getPlan(), subscription.getApplication(), subscription.getClientId());
            cache.put(new Element(subscription.getId(), key));
            cache.put(new Element(key, subscription));
        }
    }

    public void setSubscriptionRepository(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    public void setCache(Ehcache cache) {
        this.cache = cache;
    }
}
