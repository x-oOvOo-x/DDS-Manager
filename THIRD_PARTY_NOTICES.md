# Third-Party Notices

## PacketEvents

DDS Manager 的 Shadow JAR 内嵌并 relocation PacketEvents 2.13.0。

- Source: https://github.com/retrooper/packetevents
- License: GNU General Public License v3.0

GPL-3.0 本身授予符合条款时复制、修改和分发的许可，不要求另行取得作者的逐次授权。公开分发 DDS Manager 二进制时，必须同时满足 GPL-3.0：保留许可证和上游声明，并提供与二进制对应的完整源码及构建脚本。DDS Manager 因此也按 GPL-3.0 分发。

## lls-manager 与 plusls

plusls 是 lls-manager 的作者，也是 DDS Manager 的重大、奠基贡献者。DDS 的产品方向、跨服管理思路和最初需求来自 lls-manager；没有 plusls 编写的 lls-manager，就没有 DDS Manager。对此致以明确而长期的感谢。

- Author: https://github.com/plusls
- Project: https://github.com/plusls/lls-manager

lls-manager 不是 DDS Manager 的运行依赖，也未被打包。上游仓库未附带明确软件许可证，因此 DDS 保留功能与产品层面的传承，并以独立代码完成重构。技术核查记录保存在 `docs/SOURCE_REUSE_AUDIT.md`；“重大贡献者”是对奠基工作的署名和感谢，不表示 plusls 对 DDS 当前代码或发行版本负责。
