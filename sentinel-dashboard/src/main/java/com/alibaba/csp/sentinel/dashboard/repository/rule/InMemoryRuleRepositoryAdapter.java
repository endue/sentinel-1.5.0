/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.dashboard.repository.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.RuleEntity;
import com.alibaba.csp.sentinel.dashboard.discovery.MachineInfo;
import com.alibaba.csp.sentinel.util.AssertUtil;

/**
 * @author leyou
 */
public abstract class InMemoryRuleRepositoryAdapter<T extends RuleEntity> implements RuleRepository<T, Long> {

    /**
     * 记录客户端信息和对应配置的所有规则
     * {@code <machine, <id, rule>>}
     * key是客户端信息，value是个map(key是规则ID，value是规则)
     */
    private Map<MachineInfo, Map<Long, T>> machineRules = new ConcurrentHashMap<>(16);
    /**
     * 记录所有的规则
     * key是规则ID，value是规则
     */
    private Map<Long, T> allRules = new ConcurrentHashMap<>(16);
    /**
     * key是应用的名称，项目中可以通过project.name指定。若未指定，则默认解析main函数的类名作为应用名。实际项目使用中建议手动指定应用名。
     * value是个map(key是规则ID，value是规则)
     */
    private Map<String, Map<Long, T>> appRules = new ConcurrentHashMap<>(16);

    private static final int MAX_RULES_SIZE = 10000;

    @Override
    public T save(T entity) {
        // id为null则设置id
        if (entity.getId() == null) {
            entity.setId(nextId());
        }
        // 调用前置处理器，处理参数entity
        T processedEntity = preProcess(entity);
        // 将处理后的参数entity存储到内存
        if (processedEntity != null) {
            allRules.put(processedEntity.getId(), processedEntity);
            machineRules.computeIfAbsent(MachineInfo.of(processedEntity.getApp(), processedEntity.getIp(),
                processedEntity.getPort()), e -> new ConcurrentHashMap<>(32))
                .put(processedEntity.getId(), processedEntity);
            appRules.computeIfAbsent(processedEntity.getApp(), v -> new ConcurrentHashMap<>(32))
                .put(processedEntity.getId(), processedEntity);
        }

        return processedEntity;
    }

    @Override
    public List<T> saveAll(List<T> rules) {
        // TODO: check here.
        allRules.clear();
        machineRules.clear();
        appRules.clear();

        if (rules == null) {
            return null;
        }
        List<T> savedRules = new ArrayList<>(rules.size());
        for (T rule : rules) {
            savedRules.add(save(rule));
        }
        return savedRules;
    }

    @Override
    public T delete(Long id) {
        T entity = allRules.remove(id);
        if (entity != null) {
            if (appRules.get(entity.getApp()) != null) {
                appRules.get(entity.getApp()).remove(id);
            }
            machineRules.get(MachineInfo.of(entity.getApp(), entity.getIp(), entity.getPort())).remove(id);
        }
        return entity;
    }

    @Override
    public T findById(Long id) {
        return allRules.get(id);
    }

    @Override
    public List<T> findAllByMachine(MachineInfo machineInfo) {
        Map<Long, T> entities = machineRules.get(machineInfo);
        if (entities == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(entities.values());
    }

    @Override
    public List<T> findAllByApp(String appName) {
        AssertUtil.notEmpty(appName, "appName cannot be empty");
        Map<Long, T> entities = appRules.get(appName);
        if (entities == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(entities.values());
    }

    protected T preProcess(T entity) {
        return entity;
    }

    /**
     * Get next unused id.
     *
     * @return next unused id
     */
    abstract protected long nextId();
}
