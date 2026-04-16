# flux-panel转发面板 哆啦A梦转发面板

# 赞助商
<p align="center">
  <a href="https://vps.town" style="margin: 0 20px; text-align:center;">
    <img src="./doc/vpstown.png" width="300">
  </a>

  <a href="https://whmcs.as211392.com" style="margin: 0 20px; text-align:center;">
    <img src="./doc/as211392.png" width="300">
  </a>
</p>


本项目基于 [go-gost/gost](https://github.com/go-gost/gost) 和 [go-gost/x](https://github.com/go-gost/x) 两个开源库，实现了转发面板。
---
## 特性

- 支持按 **隧道账号级别** 管理流量转发数量，可用于用户/隧道配额控制
- 支持 **TCP** 和 **UDP** 协议的转发
- 支持两种转发模式：**端口转发** 与 **隧道转发**
- 可针对 **指定用户的指定隧道进行限速** 设置
- 支持配置 **单向或双向流量计费方式**，灵活适配不同计费模型
- 提供灵活的转发策略配置，适用于多种网络场景


## 部署流程
---
### Docker Compose部署
#### 快速部署
面板端(稳定版)：
```bash
curl -L "https://raw.githubusercontent.com/yannafroes84/mianbanA/main/panel_install.sh?ts=$(date +%s)" -o panel_install.sh && chmod +x panel_install.sh && bash ./panel_install.sh
```
节点端(稳定版)：
```bash
curl -L "https://raw.githubusercontent.com/yannafroes84/mianbanA/main/install.sh?ts=$(date +%s)" -o install.sh && chmod +x install.sh && bash ./install.sh

```

面板端(指定 release 版本)：
```bash
curl -L "https://raw.githubusercontent.com/yannafroes84/mianbanA/main/panel_install.sh?ts=$(date +%s)" -o panel_install.sh && chmod +x panel_install.sh && RELEASE_TAG=v2.0.8 bash ./panel_install.sh
```
节点端(指定 release 版本)：
```bash
curl -L "https://raw.githubusercontent.com/yannafroes84/mianbanA/main/install.sh?ts=$(date +%s)" -o install.sh && chmod +x install.sh && RELEASE_TAG=v2.0.8 bash ./install.sh

```

### GitHub Desktop 发布流程
1. 在 GitHub Desktop 里提交并推送代码到 `main`
2. 在 GitHub 仓库创建一个 release tag，例如 `v2.0.8`
3. 等待 `Release Deploy Assets` workflow 构建 GHCR 镜像和 release 附件
4. 在服务器上执行上面的脚本，如需固定版本可在执行前设置 `RELEASE_TAG=v2.0.8`

> 首次使用 GHCR 时，请将 GitHub Packages 设为 Public，否则服务器拉取镜像会出现 401。

#### 默认管理员账号

- **账号**: admin_user
- **密码**: admin_user

> ⚠️ 首次登录后请立即修改默认密码！


## 免责声明

本项目仅供个人学习与研究使用，基于开源项目进行二次开发。  

使用本项目所带来的任何风险均由使用者自行承担，包括但不限于：  

- 配置不当或使用错误导致的服务异常或不可用；  
- 使用本项目引发的网络攻击、封禁、滥用等行为；  
- 服务器因使用本项目被入侵、渗透、滥用导致的数据泄露、资源消耗或损失；  
- 因违反当地法律法规所产生的任何法律责任。  

本项目为开源的流量转发工具，仅限合法、合规用途。  
使用者必须确保其使用行为符合所在国家或地区的法律法规。  

**作者不对因使用本项目导致的任何法律责任、经济损失或其他后果承担责任。**  
**禁止将本项目用于任何违法或未经授权的行为，包括但不限于网络攻击、数据窃取、非法访问等。**  

如不同意上述条款，请立即停止使用本项目。  

作者对因使用本项目所造成的任何直接或间接损失概不负责，亦不提供任何形式的担保、承诺或技术支持。  


请务必在合法、合规、安全的前提下使用本项目。  

---
## ⭐ 喝杯咖啡！（USDT）

| 网络       | 地址                                                                 |
|------------|----------------------------------------------------------------------|
| BNB(BEP20) | `0xc9f1b785e7ebc6b0ec1ad7905342bce3d41a0f40`                          |
| TRC20      | `TKJuP8CwMrj8c5MxYpxV7EujJBq9kqFFwX`                                  |
| Aptos      | `0xc39a3eca65bf6a8340b6d8815949fd4bb9b96fb0a8ce4f117823a5aa8faba838`  |

[![Star History Chart](https://api.star-history.com/svg?repos=yannafroes84/mianbanA&type=Date)](https://www.star-history.com/#yannafroes84/mianbanA&Date)
