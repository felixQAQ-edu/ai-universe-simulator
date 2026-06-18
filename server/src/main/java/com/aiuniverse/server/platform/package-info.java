/**
 * CloudBase 云托管 / 微信集成的<strong>薄适配层</strong>占位(ADR-002)。
 *
 * <p>业务逻辑({@code llm/}、{@code moderation/} 及后续 event-loop / 计费 / 存档)写成平台无关的
 * Spring 模块;CloudBase 与微信 SDK 的耦合只收口在本包,以薄适配层缓解平台锁定,保留迁出路径。
 *
 * <p>骨架阶段空置:本次不接 CloudBase 部署、不接微信登录/支付/审核(见 ADR-002 实施步骤)。
 */
package com.aiuniverse.server.platform;
