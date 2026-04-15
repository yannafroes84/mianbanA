package com.admin.service.impl;

import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.ForwardGroupMapper;
import com.admin.mapper.ForwardMapper;
import com.admin.service.*;
import com.alibaba.fastjson.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * <p>
 * 缂傚倸鍊搁崐鐑芥倿閿曗偓铻為煫鍥ㄧ☉閸ㄥ倿鏌熷畡鎷岊潶濞存粏顫夌换娑㈠箣閻愬灚鍣繝娈垮枟缁秹濡甸崟顖氱闁糕剝銇炴竟鏇㈡煟閻斿摜鐭嬫繝銏★耿瀹曟劙宕归鍛闂傚倸鐗婃笟妤呭触鐎ｎ喗鐓曟繛鎴濆船瀵偓绻涢崗鐓庘枙闁哄矉绲鹃幆鏃堝焺閸愵厾顢呴梻浣侯焾椤戝洭宕伴弽顓熷仒妞ゆ洍鍋撶€规洖鐖奸崺锟犲礃閵婏附顔? * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Slf4j
@Service
public class ForwardServiceImpl extends ServiceImpl<ForwardMapper, Forward> implements ForwardService {

    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    private static final ConcurrentHashMap<Long, Object> NODE_PORT_LOCKS = new ConcurrentHashMap<>();

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    UserService userService;

    @Resource
    NodeService nodeService;

    @Resource
    ChainTunnelService chainTunnelService;

    @Resource
    ForwardPortService forwardPortService;

    @Resource
    ForwardGroupMapper forwardGroupMapper;

    @Override
    public R getAllForwards(ForwardQueryDto queryDto) {
        UserInfo currentUser = getCurrentUserInfo();
        List<ForwardWithTunnelDto> forwardList = loadVisibleForwards(currentUser);
        enrichForwardAddresses(forwardList);
        List<ForwardWithTunnelDto> filteredList = filterForwards(forwardList, queryDto);
        return R.ok(filteredList);
    }

    private List<ForwardWithTunnelDto> loadVisibleForwards(UserInfo currentUser) {
        if (currentUser.getRoleId() != 0) {
            return baseMapper.selectForwardsWithTunnelByUserId(currentUser.getUserId());
        }
        return baseMapper.selectAllForwardsWithTunnel();
    }

    private void enrichForwardAddresses(List<ForwardWithTunnelDto> forwardList) {
        if (forwardList == null || forwardList.isEmpty()) {
            return;
        }

        List<Long> tunnelIds = forwardList.stream()
                .map(ForwardWithTunnelDto::getTunnelId)
                .map(Integer::longValue)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Tunnel> tunnelMap = tunnelService.listByIds(tunnelIds).stream()
                .collect(Collectors.toMap(Tunnel::getId, t -> t));

        List<Long> forwardIds = forwardList.stream()
                .map(ForwardWithTunnelDto::getId)
                .collect(Collectors.toList());
        List<ForwardPort> allForwardPorts = forwardPortService.list(
                new QueryWrapper<ForwardPort>().in("forward_id", forwardIds)
        );
        Map<Long, List<ForwardPort>> forwardPortMap = allForwardPorts.stream()
                .collect(Collectors.groupingBy(ForwardPort::getForwardId));

        Set<Long> nodeIds = allForwardPorts.stream()
                .map(ForwardPort::getNodeId)
                .collect(Collectors.toSet());
        Map<Long, Node> nodeMap = nodeIds.isEmpty() ? Collections.emptyMap() :
                nodeService.listByIds(nodeIds).stream()
                        .collect(Collectors.toMap(Node::getId, n -> n));

        for (ForwardWithTunnelDto forward : forwardList) {
            Tunnel tunnel = tunnelMap.get(forward.getTunnelId());
            if (tunnel == null) {
                continue;
            }

            List<ForwardPort> forwardPorts = forwardPortMap.getOrDefault(forward.getId(), Collections.emptyList());
            if (forwardPorts.isEmpty()) {
                continue;
            }

            boolean useTunnelInIp = tunnel.getInIp() != null && !tunnel.getInIp().trim().isEmpty();
            Set<String> ipPortSet = new LinkedHashSet<>();

            if (useTunnelInIp) {
                List<String> ipList = new ArrayList<>();
                List<Integer> portList = new ArrayList<>();

                String[] tunnelInIps = tunnel.getInIp().split(",");
                for (String ip : tunnelInIps) {
                    if (ip != null && !ip.trim().isEmpty()) {
                        ipList.add(ip.trim());
                    }
                }

                for (ForwardPort forwardPort : forwardPorts) {
                    if (forwardPort.getPort() != null) {
                        portList.add(forwardPort.getPort());
                    }
                }

                List<String> uniqueIps = ipList.stream().distinct().toList();
                List<Integer> uniquePorts = portList.stream().distinct().toList();

                for (String ip : uniqueIps) {
                    for (Integer port : uniquePorts) {
                        ipPortSet.add(ip + ":" + port);
                    }
                }

                if (!uniquePorts.isEmpty()) {
                    forward.setInPort(uniquePorts.getFirst());
                }
            } else {
                for (ForwardPort forwardPort : forwardPorts) {
                    Node node = nodeMap.get(forwardPort.getNodeId());
                    if (node != null && node.getServerIp() != null && forwardPort.getPort() != null) {
                        ipPortSet.add(node.getServerIp() + ":" + forwardPort.getPort());
                    }
                }

                if (!forwardPorts.isEmpty() && forwardPorts.getFirst().getPort() != null) {
                    forward.setInPort(forwardPorts.getFirst().getPort());
                }
            }

            if (!ipPortSet.isEmpty()) {
                forward.setInIp(String.join(",", ipPortSet));
            }
        }
    }

    private List<ForwardWithTunnelDto> filterForwards(List<ForwardWithTunnelDto> forwardList, ForwardQueryDto queryDto) {
        if (forwardList == null || forwardList.isEmpty() || queryDto == null) {
            return forwardList;
        }

        Integer inPort = queryDto.getInPort();
        String groupName = normalizeGroupName(queryDto.getGroupName());
        if (inPort == null && groupName.isEmpty()) {
            return forwardList;
        }

        return forwardList.stream()
                .filter(forward -> inPort == null || Objects.equals(forward.getInPort(), inPort))
                .filter(forward -> groupName.isEmpty() || groupName.equals(normalizeGroupName(forward.getGroupName())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R createForward(ForwardDto forwardDto) {
        UserInfo currentUser = getCurrentUserInfo();

        Tunnel tunnel = validateTunnel(forwardDto.getTunnelId());
        if (tunnel == null) {
            return R.err("闂傚倸鍊搁崐鎼佸磹閹间礁绠熼柣鎰惈閻愬﹪鏌ㄩ弴妤€浜惧銈冨劜绾板秶鎹㈠☉銏犵闁绘劖娼欑喊宥囩磽娴ｅ壊妲归柟鍛婂▕楠炲啫煤椤忓嫀鈺呮煃鏉炴壆鍔嶇€?" );
        }

        if (tunnel.getStatus() != 1) {
            return R.err("闂傚倸鍊搁崐鎼佸磹閹间礁绠熼柣鎰惈閻愬﹪鏌ㄩ弴妤€浜惧銈冨劜瀹€鎼佸箖濡ゅ啯鍠嗛柛鏇ㄥ幗閹叉瑩鏌ｈ箛鎾剁闁荤啿鏅犳俊鎾川鐎涙ɑ娅囬梺绋挎湰缁嬫垶绂掗崫鍕垫富闁靛牆楠搁獮妤呮偨椤栨粌浠х紒顔肩墛瀵板嫰骞囬鐘插箰闂備礁鎲￠崝锔界濠婂嫮澧＄紓鍌欒兌閾忓酣宕㈡總鍛婂亯闁绘挸娴烽弳锔芥叏濮楀棗鍘存俊鎻掔墛娣囧﹪顢涘┑鍡曟睏婵犮垼娉涚€氫即寮婚埄鍐ㄧ窞閻忕偠妫勬竟澶愭⒑閸涘﹥灏伴柣鐔村姂婵?" );
        }

        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, null);
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }
        Forward forward = new Forward();
        BeanUtils.copyProperties(forwardDto, forward);
        String groupName = normalizeGroupName(forward.getGroupName());
        forward.setGroupName(groupName);
        forward.setStatus(1);
        forward.setUserId(currentUser.getUserId());
        forward.setUserName(currentUser.getUserName());
        forward.setCreatedTime(System.currentTimeMillis());
        forward.setUpdatedTime(System.currentTimeMillis());
        ensureCustomGroupExists(currentUser.getUserId(), groupName);
        List<JSONObject> success = new ArrayList<>();
        List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId()).eq("chain_type", 1));
        chainTunnels = get_port(chainTunnels, forwardDto.getInPort(), 0L);
        this.save(forward);

        for (ChainTunnel chainTunnel : chainTunnels) {

            ForwardPort forwardPort = new ForwardPort();
            forwardPort.setForwardId(forward.getId());
            forwardPort.setNodeId(chainTunnel.getNodeId());
            forwardPort.setPort(chainTunnel.getPort());
            forwardPortService.save(forwardPort);
            String serviceName = buildServiceName(forward.getId(), forward.getUserId(), permissionResult.getUserTunnel());
            Integer limiter = permissionResult.getLimiter();

            Node node = nodeService.getById(chainTunnel.getNodeId());
            if (node == null) {
                return R.err("闂傚倸鍊搁崐椋庢閿熺姴绐楅柟鎹愵嚙缁€澶屸偓鍏夊亾闁告洦鍋勯悗顓㈡煙閸忚偐鏆橀柛鏂跨Ч瀹曢潧顓兼径瀣幈闁诲繒鍋涙晶浠嬫儗婵犲伅褰掓偑閳ь剟宕规导鏉戠叀濠㈣埖鍔曠粻鑽も偓瑙勬礀濞夛箓濮€閵堝棛鍘介柟鐑樺▕瀹曟繈骞嬪┑鍫熸?" );
            }
            GostDto gostDto = GostUtil.AddAndUpdateService(serviceName, limiter, node, forward, forwardPort, tunnel, "AddService");
            if (Objects.equals(gostDto.getMsg(), "OK")) {
                JSONObject data = new JSONObject();
                data.put("node_id", node.getId());
                data.put("name", serviceName);
                success.add(data);
            } else {
                this.removeById(forward.getId());
                forwardPortService.remove(new QueryWrapper<ForwardPort>().eq("forward_id", forward.getId()));
                for (JSONObject jsonObject : success) {
                    JSONArray se = new JSONArray();
                    se.add(jsonObject.getString("name") + "_tcp");
                    se.add(jsonObject.getString("name") + "_udp");
                    GostUtil.DeleteService(jsonObject.getLong("node_id"), se);
                    return R.err(gostDto.getMsg());
                }
            }

        }
        return R.ok();
    }

    @Override
    public R updateForward(ForwardUpdateDto forwardUpdateDto) {
        // 1. 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ椤旇崵鏆楁繛瀛樼矊缂嶅﹪寮婚悢鐓庣畾鐟滃秹寮虫潏銊ｄ簻闁靛牆鎳忛崵鍥煛瀹€瀣М闁挎繄鍋ら、妤呭焵椤掍椒绻嗗ù鐘差儐閻撴洟鏌嶉崫鍕偓濠氬煀閺囩姷纾肩€光偓閸曨亝鍠氶梺绯曟櫅鐎氭澘鐣峰Ο娆炬Ь缂備讲鍋撻柍?
        UserInfo currentUser = getCurrentUserInfo();


        // 2. 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘嚩鎾寸節閻㈤潧浠︽繛鍏肩懃铻炴繝闈涙閺嗭箓鏌ㄥ┑鍡╂Ц缂佺媴缍侀弻锝夊箛椤栨稑娈忛梺鎸庢礀閸婂綊鎮￠弴鐔稿弿婵☆垰鎼埛鏃堟煟閿濆鎲鹃柡宀嬬稻楠炲﹪鏌涢弮鈧崹鐢告偩?
        Forward existForward = validateForwardExists(forwardUpdateDto.getId(), currentUser);
        if (existForward == null) {
            return R.err("闂傚倷绀侀幖顐λ囬柆宥呯？闁圭増婢樼粈鍫熺箾閸℃ê绔惧ù婊冪秺閺屾稓浠﹂崜褋鈧帡鏌嶇紒妯烩拻闁逞屽墮缁犲秹宕曢柆宥呯疇闁圭増婢樼粻娲煟濡偐甯涢柣?" );
        }


        Tunnel tunnel = validateTunnel(existForward.getTunnelId());
        if (tunnel == null) {
            return R.err("闂傚倸鍊搁崐鎼佸磹閹间礁绠熼柣鎰惈閻愬﹪鏌ㄩ弴妤€浜惧銈冨劜绾板秶鎹㈠☉銏犵闁绘劖娼欑喊宥囩磽娴ｅ壊妲归柟鍛婂▕楠炲啫煤椤忓嫀鈺呮煃鏉炴壆鍔嶇€?" );
        }

        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, null);
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }

        UserTunnel userTunnel;
        if (currentUser.getRoleId() != 0) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("濠电姷鏁搁崑鐘诲箵椤忓棗绶ら柟绋垮閸欏繘鏌ｉ姀鐘冲暈闁稿骸顦甸弻娑樷槈濞嗘劗绋囬柣搴㈣壘椤︻垶鈥︾捄銊﹀磯闁告繂瀚Σ浼存⒑閹肩偛鈧洟藝闂堟侗娼栧┑鐘宠壘瀹告繈鏌涢妷鎴濆閻撴垶绻濋姀锝庢綈婵炶尙鍠栧濠氬焺閸愩劎绐炴繝鐢靛Т閸婄懓鈻撶拠娴嬫斀?" );
            }
        } else {
            // 缂傚倸鍊搁崐鐑芥嚄閼搁潧鍨旈悗闈涙啞椤洟鏌￠崶銉ョ仼缂佺姵鐗曢埞鎴︽偐閸欏鎮欓悗鐟版啞缁诲啯绌辨繝鍥舵晬婵﹩鍓氶崐顖炴⒑閸濆嫭顥滅紒缁橈耿瀵鎮㈤崫鍕垫闁诲函缍嗛崑澶愬触閸屾粎纾藉ù锝嗙ゴ閸嬫捇宕橀崣澶嬵啋婵犵妲呴崑鍌滄濮樿泛鏄ラ柍褜鍓氶妵鍕箳閹存績鍋撹ぐ鎺戠柈闁告繂濞婅ぐ鎺撴櫜闁割偆鍣ュΛ鍕磽娴ｅ搫顎岄柛銊ョ埣瀵鈽夊Ο閿嬬€婚梺褰掑亰閸樿姤瀵奸崓绡箁Tunnel闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟杈鹃檮閸嬪鏌涢埄鍐棨闁逞屽墮閹虫捇藝閸︻厸鍋撳▓鍨灓妞ゎ厼鐗撻垾锕傚Ω閳轰胶顦ㄥ銈呯箰閹冲繘宕伴弽顓熲拻濞撴埃鍋撴繛鑹板吹閹峰綊鎮㈤悡搴ｎ槷闁硅偐琛ュ褔寮搁弮鍫熺厵閻庣數顭堟牎闁诲孩纰嶇喊宥囨崲濠靛鍨傛い鎰剁到閺嗘绱撴担鎻掍壕闂佺硶鍓濈粙鎺楁偂閵夛妇绠鹃柟瀵稿剱閻掍粙鏌ｈ箛娑楁喚闁哄备鍓濋幏鍛存濞戞帒浜鹃柡宥庡亞閻鈧箍鍎卞Λ娆撳磿閻斿吋鐓忛煫鍥э攻閸ｄ即鏌涚€ｎ偅宕屾い銏＄☉椤繈鎮℃惔銏㈡毉闂傚倷鑳剁划顖炲垂閸洘鏅濋柍鍝勬噹缁€澶愭煃瑜滈崜娑氭閹烘梻纾奸柕蹇曞Т鐎涳綁姊洪崫銉バｉ柤褰掔畺閸┿垽骞樼拠鑼吅闂佹寧娲嶉崑鎾绘倵濮橆剦妲洪柍褜鍓欑粻宥夊磿闁秴绠犻幖鎼厜缂嶆牠鏌曢崼婵愭Ч闁绘挻娲橀幈銊ヮ潨閸℃顫柤鍙夌墵閹?
            // 闂傚倸鍊搁崐椋庢閿熺姴纾婚柛娑卞枤閳瑰秹鏌ц箛姘兼綈鐎规洘鐓￠弻娑橆吋娴ｈ顔剅ward闂傚倷娴囧畷鍨叏閹惰姤鍊块柨鏇楀亾妞ゎ厼鐏濊灒闁兼祴鏅濋ˇ顖炴倵楠炲灝鍔氭い锔垮嵆瀹曠敻寮崒妤€浜鹃柣鐔告緲椤忣亝绻濋姀鈽嗙劷鐎垫澘锕畷绋课旀担鍝勫箞婵犵妲呴崹鎶藉储瑜斿鍛婃償椤兛绨诲銈嗗姧缁茶姤鎱ㄥ澶嬬厵妞ゆ牗鑹鹃弳锝団偓瑙勬礈閸犳牠銆佸鈧幃銏ゅ川婵犲骸鎮呴梻鍌氬€烽懗鍫曞箠閹炬椿鏁嬫い鎾跺亹閸欑房
            userTunnel = getUserTunnel(existForward.getUserId(), tunnel.getId().intValue());
        }

        existForward.setRemoteAddr(forwardUpdateDto.getRemoteAddr());
        existForward.setName(forwardUpdateDto.getName());
        existForward.setStrategy(forwardUpdateDto.getStrategy());
        String groupName = normalizeGroupName(forwardUpdateDto.getGroupName());
        existForward.setGroupName(groupName);
        existForward.setStatus(1);
        this.updateById(existForward);
        ensureCustomGroupExists(existForward.getUserId(), groupName);


        List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId()).eq("chain_type", 1));

        // 闂傚倸鍊烽懗鍫曞储瑜旈妴鍐╂償閵忋埄娲稿┑鐘诧工閹冲繐鐣烽幓鎺嬧偓鎺戭潩閿濆懍澹曢柣搴ゎ潐濞测晝鎹㈠┑瀣畺闁冲搫鎳庨崹鍌涖亜閹板墎绋绘い鎾崇秺濮婄粯鎷呴崷顓熻弴闂佸憡鏌ㄩ惉濂稿焵椤掍礁鍤柛鎾寸洴閹崇偤鏌嗗鍡楁濡炪倖甯婄粈渚€鎮垫导瀛樷拺闂傚牊渚楀Σ鍫曟煕婵犲喚娈滈柟顔煎槻閳规垿宕奸姀顫床?
        chainTunnels = get_port(chainTunnels, forwardUpdateDto.getInPort(), existForward.getId());



        for (ChainTunnel chainTunnel : chainTunnels) {
            String serviceName = buildServiceName(existForward.getId(), existForward.getUserId(), userTunnel);
            Integer limiter = permissionResult.getLimiter();
            Node node = nodeService.getById(chainTunnel.getNodeId());
            if (node == null) {
                return R.err("闂傚倸鍊搁崐椋庢閿熺姴绐楅柟鎹愵嚙缁€澶屸偓鍏夊亾闁告洦鍋勯悗顓㈡煙閸忚偐鏆橀柛鏂跨Ч瀹曢潧顓兼径瀣幈闁诲繒鍋涙晶浠嬫儗婵犲伅褰掓偑閳ь剟宕规导鏉戠叀濠㈣埖鍔曠粻鑽も偓瑙勬礀濞夛箓濮€閵堝棛鍘介柟鐑樺▕瀹曟繈骞嬪┑鍫熸?" );
            }
            ForwardPort forwardPort = forwardPortService.getOne(new QueryWrapper<ForwardPort>().eq("forward_id", existForward.getId()).eq("node_id", node.getId()));
            if (forwardPort == null) {
                return R.err("闂傚倸鍊搁崐椋庢閿熺姴绐楅柟鎹愵嚙缁€澶屸偓鍏夊亾闁告洦鍋勯悗顓㈡煙閸忚偐鏆橀柛鏂跨Ч瀹曢潧顓兼径瀣幈闁诲繒鍋涙晶浠嬫儗婵犲伅褰掓偑閳ь剟宕规导鏉戠叀濠㈣埖鍔曠粻鑽も偓瑙勬礀濞夛箓濮€閵堝棛鍘介柟鐑樺▕瀹曟繈骞嬪┑鍫熸?");
            }
            forwardPort.setPort(chainTunnel.getPort());
            forwardPortService.updateById(forwardPort);
            GostDto gostDto = GostUtil.AddAndUpdateService(serviceName, limiter, node, existForward, forwardPort, tunnel, "UpdateService");
            if (!Objects.equals(gostDto.getMsg(), "OK")) return R.err(gostDto.getMsg());
        }

        return R.ok();
    }

    @Override
    public R deleteForward(Long id) {

        // 1. 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ椤旇崵鏆楁繛瀛樼矊缂嶅﹪寮婚悢鐓庣畾鐟滃秹寮虫潏銊ｄ簻闁靛牆鎳忛崵鍥煛瀹€瀣М闁挎繄鍋ら、妤呭焵椤掍椒绻嗗ù鐘差儐閻撴洟鏌嶉崫鍕偓濠氬煀閺囩姷纾肩€光偓閸曨亝鍠氶梺绯曟櫅鐎氭澘鐣峰Ο娆炬Ь缂備讲鍋撻柍?
        UserInfo currentUser = getCurrentUserInfo();


        // 2. 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘嚩鎾寸節閻㈤潧浠︽繛鍏肩懃铻炴繝闈涙閺嗭箓鏌ㄥ┑鍡╂Ц缂佺媴缍侀弻锝夊箛椤栨稑娈忛梺鎸庢礀閸婂綊鎮￠弴鐔稿弿婵☆垰鎼埛鏃堟煟閿濆鎲鹃柡宀嬬稻楠炲﹪鏌涢弮鈧崹鐢告偩?
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("闂傚倷绀侀幖顐λ囬柆宥呯？闁圭増婢樼粈鍫熺箾閸℃ê绔惧ù婊冪秺閺屾稓浠﹂崜褋鈧帡鏌嶇紒妯烩拻闁逞屽墮缁犲秹宕曢柆宥呯疇闁圭増婢樼粻娲煟濡偐甯涢柣?" );
        }


        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("闂傚倸鍊搁崐鎼佸磹閹间礁绠熼柣鎰惈閻愬﹪鏌ㄩ弴妤€浜惧銈冨劜绾板秶鎹㈠☉銏犵闁绘劖娼欑喊宥囩磽娴ｅ壊妲归柟鍛婂▕楠炲啫煤椤忓嫀鈺呮煃鏉炴壆鍔嶇€?" );
        }

        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, null);
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }
        UserTunnel userTunnel = null;
        if (currentUser.getRoleId() != 0) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("濠电姷鏁搁崑鐘诲箵椤忓棗绶ら柟绋垮閸欏繘鏌ｉ姀鐘冲暈闁稿骸顦甸弻娑樷槈濞嗘劗绋囬柣搴㈣壘椤︻垶鈥︾捄銊﹀磯闁告繂瀚Σ浼存⒑閹肩偛鈧洟藝闂堟侗娼栧┑鐘宠壘瀹告繈鏌涢妷鎴濆閻撴垶绻濋姀锝庢綈婵炶尙鍠栧濠氬焺閸愩劎绐炴繝鐢靛Т閸婄懓鈻撶拠娴嬫斀?" );
            }
        } else {
            // 缂傚倸鍊搁崐鐑芥嚄閼搁潧鍨旈悗闈涙啞椤洟鏌￠崶銉ョ仼缂佺姵鐗曢埞鎴︽偐閸欏鎮欓悗鐟版啞缁诲啯绌辨繝鍥舵晬婵﹩鍓氶崐顖炴⒑閸濆嫭顥滅紒缁橈耿瀵鎮㈤崫鍕垫闁诲函缍嗛崑澶愬触閸屾粎纾藉ù锝嗙ゴ閸嬫捇宕橀崣澶嬵啋婵犵妲呴崑鍌滄濮樿泛鏄ラ柍褜鍓氶妵鍕箳閹存績鍋撹ぐ鎺戠柈闁告繂濞婅ぐ鎺撴櫜闁割偆鍣ュΛ鍕磽娴ｅ搫顎岄柛銊ョ埣瀵鈽夊Ο閿嬬€婚梺褰掑亰閸樿姤瀵奸崓绡箁Tunnel闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟杈鹃檮閸嬪鏌涢埄鍐棨闁逞屽墮閹虫捇藝閸︻厸鍋撳▓鍨灓妞ゎ厼鐗撻垾锕傚Ω閳轰胶顦ㄥ銈呯箰閹冲繘宕伴弽顓熲拻濞撴埃鍋撴繛鑹板吹閹峰綊鎮㈤悡搴ｎ槷闁硅偐琛ュ褔寮搁弮鍫熺厵閻庣數顭堟牎闁诲孩纰嶇喊宥囨崲濠靛鍨傛い鎰剁到閺嗘绱撴担鎻掍壕闂佺硶鍓濈粙鎺楁偂閵夛妇绠鹃柟瀵稿剱閻掍粙鏌ｈ箛娑楁喚闁哄备鍓濋幏鍛存濞戞帒浜鹃柡宥庡亞閻鈧箍鍎卞Λ娆撳磿閻斿吋鐓忛煫鍥э攻閸ｄ即鏌涚€ｎ偅宕屾い銏＄☉椤繈鎮℃惔銏㈡毉闂傚倷鑳剁划顖炲垂閸洘鏅濋柍鍝勬噹缁€澶愭煃瑜滈崜娑氭閹烘梻纾奸柕蹇曞Т鐎涳綁姊洪崫銉バｉ柤褰掔畺閸┿垽骞樼拠鑼吅闂佹寧娲嶉崑鎾绘倵濮橆剦妲洪柍褜鍓欑粻宥夊磿闁秴绠犻幖鎼厜缂嶆牠鏌曢崼婵愭Ч闁绘挻娲橀幈銊ヮ潨閸℃顫柤鍙夌墵閹?
            // 闂傚倸鍊搁崐椋庢閿熺姴纾婚柛娑卞枤閳瑰秹鏌ц箛姘兼綈鐎规洘鐓￠弻娑橆吋娴ｈ顔剅ward闂傚倷娴囧畷鍨叏閹惰姤鍊块柨鏇楀亾妞ゎ厼鐏濊灒闁兼祴鏅濋ˇ顖炴倵楠炲灝鍔氭い锔垮嵆瀹曠敻寮崒妤€浜鹃柣鐔告緲椤忣亝绻濋姀鈽嗙劷鐎垫澘锕畷绋课旀担鍝勫箞婵犵妲呴崹鎶藉储瑜斿鍛婃償椤兛绨诲銈嗗姧缁茶姤鎱ㄥ澶嬬厵妞ゆ牗鑹鹃弳锝団偓瑙勬礈閸犳牠銆佸鈧幃銏ゅ川婵犲骸鎮呴梻鍌氬€烽懗鍫曞箠閹炬椿鏁嬫い鎾跺亹閸欑房
            userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        }

        List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId()).eq("chain_type", 1));
        for (ChainTunnel chainTunnel : chainTunnels) {

            String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
            Node node = nodeService.getById(chainTunnel.getNodeId());
            if (node == null) {
                return R.err("闂傚倸鍊搁崐椋庢閿熺姴绐楅柟鎹愵嚙缁€澶屸偓鍏夊亾闁告洦鍋勯悗顓㈡煙閸忚偐鏆橀柛鏂跨Ч瀹曢潧顓兼径瀣幈闁诲繒鍋涙晶浠嬫儗婵犲伅褰掓偑閳ь剟宕规导鏉戠叀濠㈣埖鍔曠粻鑽も偓瑙勬礀濞夛箓濮€閵堝棛鍘介柟鐑樺▕瀹曟繈骞嬪┑鍫熸?" );
            }

            JSONArray services = new JSONArray();
            services.add(serviceName + "_tcp");
            services.add(serviceName + "_udp");
            GostUtil.DeleteService(node.getId(), services);
        }
        forwardPortService.remove(new QueryWrapper<ForwardPort>().eq("forward_id", id));
        this.removeById(id);
        return R.ok();
    }

    @Override
    public R pauseForward(Long id) {
        return changeForwardStatus(id, 0, "PauseService");
    }

    @Override
    public R resumeForward(Long id) {
        return changeForwardStatus(id, 1, "ResumeService");
    }

    @Override
    public R forceDeleteForward(Long id) {
        UserInfo currentUser = getCurrentUserInfo();
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("缂傚倸鍊搁崐鐑芥倿閿曗偓铻為煫鍥ㄧ☉閸ㄥ倿鏌熷畡鎷岊潶濞存粏顫夌换娑㈠箣閻愬灚鍣繝娈垮枟缁秹濡甸崟顖氱闁糕剝銇炴竟鏇㈡煟閻斿摜鐭嬫繝銏★耿瀹曟劙宕烽鐘电厯婵犮垼娉涢…顒勫触鐎ｎ喗鐓曟繛鎴濆船閻忥繝鏌熼幓鎺嬪仮婵?" );
        }
        this.removeById(id);
        forwardPortService.remove(new QueryWrapper<ForwardPort>().eq("forward_id", id));
        return R.ok();
    }

    @Override
    public R diagnoseForward(Long id) {
        // 1. 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ椤旇崵鏆楁繛瀛樼矊缂嶅﹪寮婚悢鐓庣畾鐟滃秹寮虫潏銊ｄ簻闁靛牆鎳忛崵鍥煛瀹€瀣М闁挎繄鍋ら、妤呭焵椤掍椒绻嗗ù鐘差儐閻撴洟鏌嶉崫鍕偓濠氬煀閺囩姷纾肩€光偓閸曨亝鍠氶梺绯曟櫅鐎氭澘鐣峰Ο娆炬Ь缂備讲鍋撻柍?
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘嚩鎾寸節閻㈤潧浠︽繛鍏肩懃铻炴繝闈涙閺嗭箓鏌ㄥ┑鍡╂Ц缂佺媴缍侀弻锝夊箛椤栨稑娈忛梺鎸庢礀閸婂綊鎮￠弴鐔稿弿婵☆垰鎼埛鏃堟煟閿濆鎲鹃柡宀嬬稻楠炲﹪鏌涢弮鈧崹鐢告偩閻戣姤鍊婚柦妯猴級閳哄懏鐓忛柛顐ｇ箖瀹告繈鏌熼崘鑼ф慨濠傛惈鐓ょ紓浣姑埢蹇涙⒑閸涘﹥鐓ョ紒澶屾嚀閻ｇ兘宕崟銊︻潔闂侀潧绻掓慨鎾偟濡崵绡€闁靛骏绲剧涵鐐亜閹存繃顥犵紒杈╁仱閸┾偓妞ゆ帒瀚埛鎺楁煕鐏炴崘澹樻い蹇ｄ邯閺屾盯寮埀顒勬晝閵忋們鈧礁鈻庨幘鍐插敤濡炪倖鎸鹃崯鍧楀箯?
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("闂傚倷绀侀幖顐λ囬柆宥呯？闁圭増婢樼粈鍫熺箾閸℃ê绔惧ù婊冪秺閺屾稓浠﹂崜褋鈧帡鏌嶇紒妯烩拻闁逞屽墮缁犲秹宕曢柆宥呯疇闁圭増婢樼粻娲煟濡偐甯涢柣?" );
        }

        // 3. 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ椤旂厧顫梺鎶芥敱濞叉粓骞夐幖浣哥睄闁逞屽墰缁顓兼径瀣偓閿嬨亜閹烘垵鈧敻寮ㄩ幎鑺モ拺闁哄倶鍎插▍鍛存煕閻斿弶娅囨俊?
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("闂傚倸鍊搁崐鎼佸磹閹间礁绠熼柣鎰惈閻愬﹪鏌ㄩ弴妤€浜惧銈冨劜绾板秶鎹㈠☉銏犵闁绘劖娼欑喊宥囩磽娴ｅ壊妲归柟鍛婂▕楠炲啫煤椤忓嫀鈺呮煃鏉炴壆鍔嶇€?" );
        }

        // 4. 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ椤旂厧顫梺鎶芥敱濞叉粓骞夐幖浣哥睄闁逞屽墰缁顓奸崱鎰簼闂佸憡鍔忛弲娑㈠礈椤撱垺鈷戦柛娑橈工閻忥妇绱掑鎯т紣inTunnel濠电姷鏁搁崕鎴犲緤閽樺娲偐鐠囪尙顦┑鐘绘涧濞层倝顢?
        List<ChainTunnel> chainTunnels = chainTunnelService.list(
                new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId())
        );

        if (chainTunnels.isEmpty()) {
            return R.err("闂傚倸鍊搁崐鎼佸磹閹间礁绠熼柣鎰惈閻愬﹪鏌ㄩ弴妤€浜惧銈冨劜瀹€绋款潖缂佹ɑ濯撮悷娆忓閸戯紕绱撴担鍓叉Ц闁绘牕銈搁獮鍐锤濡も偓楠炪垺淇婇姘辨癁闁哄鐗犻幃妤呯嵁閸喖濮庨梺纭呮珪閸旀瑥顕ｉ崘宸僵闁煎摜鏁搁崢?" );
        }

        // 闂傚倸鍊风粈渚€骞夐敍鍕殰闁圭儤鍤﹀☉妯锋婵﹩鍘兼禒蹇擃渻閵堝棗濮ч梻鍕瀹曢潧顓兼径瀣幈闁诲繒鍋涙晶浠嬫儗婵犲伅?
        List<ChainTunnel> inNodes = chainTunnels.stream()
                .filter(ct -> ct.getChainType() == 1)
                .toList();

        Map<Integer, List<ChainTunnel>> chainNodesMap = chainTunnels.stream()
                .filter(ct -> ct.getChainType() == 2)
                .collect(Collectors.groupingBy(
                        ct -> ct.getInx() != null ? ct.getInx() : 0,
                        Collectors.toList()
                ));

        List<List<ChainTunnel>> chainNodesList = chainNodesMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();

        List<ChainTunnel> outNodes = chainTunnels.stream()
                .filter(ct -> ct.getChainType() == 3)
                .toList();

        List<DiagnosisResult> results = new ArrayList<>();
        String[] remoteAddresses = forward.getRemoteAddr().split(",");

        // 闂傚倸鍊风粈渚€骞栭銈囩煋闁绘垶鏋荤紞鏍ь熆鐠虹尨鍔熼柡鍡愬€曢妴鎺戭潩閿濆懍澹曟繝娈垮枛閿曘儱鐣峰鈧棟闁荤喐鍣撮悷閭︾叆闁告劑鍔夐幐鍐⒑缂佹澹樺褌绮欓獮蹇涙偐缂佹ê娈ゅ銈嗗灱濡嫭绂嶆ィ鍐╃厽闁硅揪绲鹃ˉ澶岀磼閻橆喖鍔ら柟鍙夋倐楠炲鏁傜悰鈥充壕濞撴埃鍋撴鐐差儔閹瑧鍠婂Ο鑽や簷闂佽楠哥粻宥夊磿闁秴绠犻煫鍥ㄧ☉绾惧綊姊洪鈧粔鐢告偂閻斿吋鐓忓┑鐐茬仢閸旀碍銇勮箛锝呭⒋闁哄矉缍侀弫鍌炴偩鐏炶В鎷伴柣搴㈩問閸ｎ噣宕滈悢绗衡偓浣割潨閳ь剟骞冮埡鍛瀭妞ゆ劧绲介弸鎴︽⒒?
        if (tunnel.getType() == 1) {
            // 缂傚倸鍊搁崐鐑芥倿閿曗偓铻為煫鍥ㄧ☉閸ㄥ倿鏌熷畡鎷岊潶濞存粏顫夌换娑㈠箣閻愬灚鍣繝娈垮枟缁秹濡甸崟顖氱闁糕剝銇炴竟鏇㈡煟閻斿摜鐭嬫繝銏★耿瀹曟劙宕稿Δ鈧弸浣衡偓骞垮劚閹峰顭囬埡鍌樹簻闁瑰搫妫楁禍楣冩倵濞堝灝鏋熷┑鐐诧躬瀵鈽夊Ο閿嬬€诲┑鐐叉閸ㄧ鈪叉繝鐢靛Х椤ｈ棄危閸涙潙鐭楅柛鎰靛枛缁犳牗绻涢崱妯诲碍闁圭鍩栭妵鍕箻鐠虹儤鐏侀梺鍛婂灣缁瑥顫忓ú顏呯劵婵炴垶绮犻崝鍛攽閻愬弶鍣归柛銈呪敂 ping闂傚倸鍊烽懗鍫曞磿閻㈢鐤鹃柍鍝勬噹缁愭淇婇妶鍛櫤闁稿顦甸弻銊モ攽閸♀晜笑闂佸湱鎳撻悥濂稿蓟閳╁啫绶為悗锝庝憾閸ゅ绱?
            for (ChainTunnel inNode : inNodes) {
                Node node = nodeService.getById(inNode.getNodeId());
                if (node != null) {
                    for (String remoteAddress : remoteAddresses) {
                        String targetIp = extractIpFromAddress(remoteAddress);
                        int targetPort = extractPortFromAddress(remoteAddress);
                        if (targetIp != null && targetPort != -1) {
                            DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                    node, targetIp, targetPort,
                                    "闂傚倸鍊烽懗鍫曗€﹂崼銏″床闁割偁鍎辩壕鍧楀级閸偄浜栧ù?" + node.getName() + ")->闂傚倸鍊烽懗鍫曞磿閻㈢鐤鹃柍鍝勬噹缁愭淇婇妶鍛櫤闁?" + remoteAddress + ")"
                            );
                            result.setFromChainType(1);
                            results.add(result);
                        }
                    }
                }
            }
        } else if (tunnel.getType() == 2) {
            // 闂傚倸鍊搁崐鎼佸磹閹间礁绠熼柣鎰惈閻愬﹪鏌ㄩ弴妤€浜惧銈冨劜瀹€鎼佸蓟閳╁啫绶為悘鐐舵婢瑰姊洪崨濠冨鞍闁荤喆鍔戞俊鐢稿箛閺夎法锛滈梺缁樺姇閻°劌鈻嶉弽褉鏀芥い鏃€鏋绘笟娑㈡煕濮椻偓缁犳牗淇婇崼鏇炵妞ゆ棁妫勬禒顖炴⒑閹肩偛鍔橀柛鏂跨Ф缁牓鍩€椤掑嫭鈷戝ù鍏肩懅缁夌敻鏌涢幘璺烘瀻妞ゆ洩缍侀、鏇㈡晝閳ь剛绮荤紒妯镐簻闁哄啫娲よ闂佹悶鍊栭崹鍧楀蓟?            // 1. 闂傚倸鍊烽懗鍫曗€﹂崼銏″床闁割偁鍎辩壕鍧楀级閸偄浜栧ù?>缂傚倸鍊搁崐鐑芥倿閿曞倶鈧啳绠涘☉妯碱槯濠电偞鍨跺銊╁础濮樿埖鍊甸梻鍫熺⊕閸熺偟鈧娲栭ˇ杈╂閹烘垟妲堟俊顖涙绾偓缂傚倷璁查崑鎾绘煕閺囥劌鐏￠柣鎾寸懇閹鈽夊▎妯煎姺婵犫拃灞奸偗闁哄本鐩俊鑸垫償閳ュ磭鐫勯柣搴ゎ潐濞测晝绱炴笟鈧顐﹀箛椤栨粎鏉搁梺鍝勬川婵厼危?
            for (ChainTunnel inNode : inNodes) {
                Node fromNode = nodeService.getById(inNode.getNodeId());

                if (fromNode != null) {
                    if (!chainNodesList.isEmpty()) {
                        for (ChainTunnel firstChainNode : chainNodesList.getFirst()) {
                            Node toNode = nodeService.getById(firstChainNode.getNodeId());
                            if (toNode != null) {
                                DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                        fromNode, toNode.getServerIp(), firstChainNode.getPort(),
                                        "闂傚倸鍊烽懗鍫曗€﹂崼銏″床闁割偁鍎辩壕鍧楀级閸偄浜栧ù?" + fromNode.getName() + ")->缂?闂?" + toNode.getName() + ")"
                                );
                                result.setFromChainType(1);
                                result.setToChainType(2);
                                result.setToInx(firstChainNode.getInx());
                                results.add(result);
                            }
                        }
                    } else if (!outNodes.isEmpty()) {
                        for (ChainTunnel outNode : outNodes) {
                            Node toNode = nodeService.getById(outNode.getNodeId());
                            if (toNode != null) {
                                DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                        fromNode, toNode.getServerIp(), outNode.getPort(),
                                        "闂傚倸鍊烽懗鍫曗€﹂崼銏″床闁割偁鍎辩壕鍧楀级閸偄浜栧ù?" + fromNode.getName() + ")->闂傚倸鍊风粈渚€骞夐敓鐘插瀭闁汇垻顭堢粻瑙勩亜閹扳晛鍔樺ù?" + toNode.getName() + ")"
                                );
                                result.setFromChainType(1);
                                result.setToChainType(3);
                                results.add(result);
                            }
                        }
                    }
                }
            }

            // 2. 闂傚倸鍊搁崐鐑芥嚄閸洖纾婚柟鎯х－閺嗭附鎱ㄥ璇蹭壕闂佽鍨伴惌鍌炵嵁閸℃稈鈧牠鍨鹃搹顐ョ闂侀€炲苯澧剧紓宥呮瀹曘垽骞嗛‖?
            for (int i = 0; i < chainNodesList.size(); i++) {
                List<ChainTunnel> currentHop = chainNodesList.get(i);

                for (ChainTunnel currentNode : currentHop) {
                    Node fromNode = nodeService.getById(currentNode.getNodeId());

                    if (fromNode != null) {
                        if (i + 1 < chainNodesList.size()) {
                            for (ChainTunnel nextNode : chainNodesList.get(i + 1)) {
                                Node toNode = nodeService.getById(nextNode.getNodeId());
                                if (toNode != null) {
                                    DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                            fromNode, toNode.getServerIp(), nextNode.getPort(),
                                            "Hop " + (i + 1) + "(" + fromNode.getName() + ")->Hop " + (i + 2) + "(" + toNode.getName() + ")"
                                    );
                                    result.setFromChainType(2);
                                    result.setFromInx(currentNode.getInx());
                                    result.setToChainType(2);
                                    result.setToInx(nextNode.getInx());
                                    results.add(result);
                                }
                            }
                        } else if (!outNodes.isEmpty()) {
                            for (ChainTunnel outNode : outNodes) {
                                Node toNode = nodeService.getById(outNode.getNodeId());
                                if (toNode != null) {
                                    DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                            fromNode, toNode.getServerIp(), outNode.getPort(),
                                            "Hop " + (i + 1) + "(" + fromNode.getName() + ")->Exit(" + toNode.getName() + ")"
                                    );
                                    result.setFromChainType(2);
                                    result.setFromInx(currentNode.getInx());
                                    result.setToChainType(3);
                                    results.add(result);
                                }
                            }
                        }
                    }
                }
            }

            // 3. 闂傚倸鍊风粈渚€骞夐敓鐘插瀭闁汇垻顭堢粻瑙勩亜閹扳晛鍔樺ù?>闂傚倸鍊烽懗鍫曞磿閻㈢鐤鹃柍鍝勬噹缁愭淇婇妶鍛櫤闁稿顦甸弻銊モ攽閸♀晜笑闂佸湱鎳撻悥濂稿蓟閳╁啫绶為悗锝庝憾閸ゅ绱?
            for (ChainTunnel outNode : outNodes) {
                Node node = nodeService.getById(outNode.getNodeId());
                if (node != null) {
                    for (String remoteAddress : remoteAddresses) {
                        String targetIp = extractIpFromAddress(remoteAddress);
                        int targetPort = extractPortFromAddress(remoteAddress);
                        if (targetIp != null && targetPort != -1) {
                            DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                    node, targetIp, targetPort,
                                    "闂傚倸鍊风粈渚€骞夐敓鐘插瀭闁汇垻顭堢粻瑙勩亜閹扳晛鍔樺ù?" + node.getName() + ")->闂傚倸鍊烽懗鍫曞磿閻㈢鐤鹃柍鍝勬噹缁愭淇婇妶鍛櫤闁?" + remoteAddress + ")"
                            );
                            result.setFromChainType(3);
                            results.add(result);
                        }
                    }
                }
            }
        }

        // 闂傚倸鍊风粈渚€骞栭锔绘晞闁告侗鍨崑鎾愁潩閻撳骸顫紓浣介哺閹瑰洭鐛鈧幊婊堟濞戞艾閰辨繝鐢靛Х閺佸憡鎱ㄩ幘顔肩柈妞ゆ牜鍋為崐鍨归悩宸剱闁绘挻鐟╁Λ鍛搭敆娴ｅ摜绁烽梺鍛婃⒐瀹€鎼佸蓟?
        Map<String, Object> diagnosisReport = new HashMap<>();
        diagnosisReport.put("forwardId", id);
        diagnosisReport.put("forwardName", forward.getName());
        diagnosisReport.put("tunnelType", tunnel.getType() == 1 ? "direct" : "chain");
        diagnosisReport.put("results", results);
        diagnosisReport.put("timestamp", System.currentTimeMillis());

        return R.ok(diagnosisReport);
    }

    @Override
    @Transactional
    public R updateForwardOrder(Map<String, Object> params) {
        // 1. 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ椤旇崵鏆楁繛瀛樼矊缂嶅﹪寮婚悢鐓庣畾鐟滃秹寮虫潏銊ｄ簻闁靛牆鎳忛崵鍥煛瀹€瀣М闁挎繄鍋ら、妤呭焵椤掍椒绻嗗ù鐘差儐閻撴洟鏌嶉崫鍕偓濠氬煀閺囩姷纾肩€光偓閸曨亝鍠氶梺绯曟櫅鐎氭澘鐣峰Ο娆炬Ь缂備讲鍋撻柍?
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 濠电姴鐥夐弶搴撳亾濡や焦鍙忛柟缁㈠枟閸庢銆掑锝呬壕闂佽鍨悞锕€顕ラ崟顖氱疀妞ゆ帒鍊婚崢顒勬⒒娴ｄ警鐒鹃柡鍫墰缁瑩骞掗幋鏃€鐏?
        if (!params.containsKey("forwards")) {
            return R.err("缂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸缁犺銇勯幇鍫曟闁稿骸绉归弻锝夊Ψ閿斿墽鎳檙wards闂傚倸鍊风粈渚€骞夐敓鐘冲仭闁靛鏅涚壕鍦喐閻楀牆绗掓慨?" );
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> forwardsList = (List<Map<String, Object>>) params.get("forwards");
        if (forwardsList == null || forwardsList.isEmpty()) {
            return R.err("forwards闂傚倸鍊风粈渚€骞夐敓鐘冲仭闁靛鏅涚壕鍦喐閻楀牆绗掓慨瑙勭叀閺岋綁寮幐搴㈠枑闂佸磭绮濠氬焵椤掆偓缁犲秹宕曢柆宥嗗亱闁糕剝绋戦崒銊╂煛婢跺鐏嶆俊鎻掔墦閺岀喖骞戦幇鍨秷濡炪倕绻掓繛鈧柡?" );
        }

        // 3. 濠电姴鐥夐弶搴撳亾濡や焦鍙忛柟缁㈠枟閸庢銆掑锝呬壕闂佽鍨悞锕€顕ラ崟顖氱疀妞ゆ挾鍋樼紓鎾绘⒒娓氣偓濞佳団€﹂鐘典笉闁硅揪闄勯崑鍌炴煛閸ャ儱鐏柣鎾寸☉椤法鎹勯悮鏉戜紣闂佸搫妫寸徊鐐┍婵犲洦鍊锋い鎺嶉檷娴犮垽姊烘潪鎵槮缂佸鎳撻悾鐑芥偂鎼存ɑ鏂€闂佺硶鍓濋敃銏ゅ极椤忓牊鈷掑ù锝呮惈鐢爼鏌ｉ妶鍛闁逛究鍔戞俊鍫曞触閵堝棙銇濈€规洜顭堣灃闁逞屽墮铻炴慨妞诲亾闁哄瞼鍠庨悾锟犲箯鐏炵瓔浼冩繝鐢靛仜閻楀﹥绔熼崱娑樼劦妞ゆ帒鍠氬鎰版煟閳╁啯绀冪紒鍌氱Ч椤㈡鍩€椤掑嫬鐓濋柟鎹愵嚙缁狅綁鏌ｉ幇顓熺稇妞ゎ偀鈧剚娓婚柕鍫濇閸у﹪鏌涚€ｎ偅灏甸柟鍙夋倐瀵爼宕归鐟颁壕闁秆勵殔閺?
        if (currentUser.getRoleId() != 0) {
            // 闂傚倸鍊风粈渚€骞栭锕€绠悗锝庡枛闂傤垶鏌ㄥ┑鍡╂Ц闁绘帒鐏氶妵鍕箳瀹ュ洤濡介梺姹囧妽閸ㄥ潡寮婚埄鍐╁闂傚牊绋堥崑鎾斥攽鐎ｎ剙绁﹂柣搴秵娴滅偤寮崇€ｎ喗鐓涘璺侯儏椤曟粓姊哄▎鎯у籍婵﹥妞介幃鈩冩償閿濆棛鈧喗绻濆▓鍨灁闁稿﹥娲滈崣鍛存⒑缁夊棗瀚峰▓鏃€淇婇锝忚€块柡宀€鍠庨悾锟犲箯鐏炵瓔浼冩繝鐢靛仜閻楀﹥绔熼崱娑樼劦妞ゆ帒鍠氬鎰版煟閳╁啯绀冪紒鍌氱Ч椤㈡鍩€椤掑嫬鐓濋柟鎹愵嚙缁狅綁鏌ｉ幇顓熺稇妞ゎ偀鈧剚娓婚柕鍫濇閸у﹪鏌涚€ｎ偅灏甸柟?
            List<Long> forwardIds = forwardsList.stream()
                    .map(item -> Long.valueOf(item.get("id").toString()))
                    .collect(Collectors.toList());

            // 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘埈娼ユ繝鐢靛Л閹峰啴宕橀顖嗗應鍋撳▓鍨灈闁诲繑鑹鹃銉╁礋椤掍礁鍔呴梺闈涙禋濞煎潡宕戦弽顓熲拻濞达絽鎲￠崯鐐烘煟閻曞倻鐣电€规洖婀遍幑鍕媴閺囶亞绉鐐寸墬閹峰懎顫㈢仦婵囨礋濮婃椽骞愭惔銏紩闂佺顑嗛幑鍥蓟閿濆鏁冮柨娑掓櫆閸ㄥ湱鍒掔€ｎ亶鍚嬮柛鈩兠崝鍛存⒑閹稿海鈽夐悗姘煎枟閻″繘姊婚崒娆戭槮闁硅绻濋獮鎰板传閵壯呯厠闂佸壊鍋呭ú鏍几娓氣偓閺屾洟宕煎┑鎰︾紓?
            QueryWrapper<Forward> queryWrapper = new QueryWrapper<>();
            queryWrapper.in("id", forwardIds);
            queryWrapper.eq("user_id", currentUser.getUserId());

            long count = this.count(queryWrapper);
            if (count != forwardIds.size()) {
                return R.err("闂傚倸鍊风粈渚€骞夐敓鐘冲仭妞ゆ牗绋撻々鏌ユ煥濠靛棭妲哥紒鈧崒娑欏弿婵＄偠顕ф禍楣冩倵濞堝灝鏋涙い鏇ㄥ弮閸┾偓妞ゆ帊鑳堕埊鏇㈡嫅闁秵鐓熼柟缁㈠灠娴滈箖姊婚崒娆掑厡闁告鍥モ偓鍐╂償閵忋埄娲稿┑鐘诧工閹冲繐鐣烽幓鎺嬧偓鎺戭潩閿濆懍澹曢梻浣筋嚃閸ㄤ即宕弶鎴犳殾闁绘梻鈷堥弫濠囨煟閿濆懓顫﹂柛瀣墵濮婄粯鎷呴崨濠傛殘闂佽崵鍣︾粻鎾崇暦瑜版帒绀堝ù锝堫潐濞堜即姊虹憴鍕姢闁宦板姂瀹?" );
            }
        }

        // 4. 闂傚倸鍊风粈浣虹礊婵犲偆鐒界憸蹇曟閻愬绡€闁搞儜鍥紬婵犵數鍋涘Ο濠冪濠婂牆纭€闁规儼濮ら崐鍨箾閹寸儐浼嗛柟杈鹃檮閸嬪倿鏌嶈閸撶喎顫忓ú顏勬嵍妞ゆ挾鍋涙俊娲煟閵忊晛鐏￠悽顖滃仱閹?
        List<Forward> forwardsToUpdate = new ArrayList<>();
        for (Map<String, Object> forwardData : forwardsList) {
            Long id = Long.valueOf(forwardData.get("id").toString());
            Integer inx = Integer.valueOf(forwardData.get("inx").toString());

            Forward forward = new Forward();
            forward.setId(id);
            forward.setInx(inx);
            forwardsToUpdate.add(forward);
        }

        // 5. 闂傚倸鍊风粈浣革耿闁秵鍋￠柟鎯版楠炪垽鏌嶉崫鍕偓褰掑级閹间焦鈷掑ù锝呮憸缁夌儤绻涙担鍐叉噽缁€濠傗攽閻樺弶鎼愰梻鍌ゅ灡缁绘稑顔忛鑽ょ泿闂佸憡顨嗘繛濠囧箖濡も偓閳藉鈻嶉搹顐㈢伌闁诡噯绻濋崺鈧?
        this.updateBatchById(forwardsToUpdate);
        return R.ok();


    }


    private R changeForwardStatus(Long id, int targetStatus, String gostMethod) {
        UserInfo currentUser = getCurrentUserInfo();
        if (currentUser.getRoleId() != 0) {
            User user = userService.getById(currentUser.getUserId());
            if (user == null) return R.err("闂傚倸鍊烽悞锕€顪冮崹顕呯劷闁秆勵殔缁€澶屸偓骞垮劚椤︻垶寮伴妷锔剧闁瑰鍋熼幊鍛存煃缂佹ɑ鈷掗柍褜鍓欑粻宥夊磿闁秴绠犻柟鐗堟緲缁犳椽鏌ｅΟ鑲╁笡闁?" );
            if (user.getStatus() == 0) return R.err("闂傚倸鍊烽悞锕€顪冮崹顕呯劷闁秆勵殔缁€澶屸偓骞垮劚椤︻垶寮伴妷锔剧闁瑰鍎戞笟娑㈡煕閻旈攱鍤囬柡宀€鍠撶槐鎺懳熼搹鍦噯闂備胶绮幐鎼佸磹閸ф钃熼柨鐔哄Т闁卞洭鏌曡箛鏇炐ｉ柣搴ㄧ畺濮婃椽骞栭悙鎻捨╅梺鍛婃煥闁帮綁鐛崘顕呮晪闁逞屽墴閵嗕線寮撮姀鐘栄囧箹濞ｎ剙鐏柣鎾村灴濮?" );
        }
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("闂傚倷绀侀幖顐λ囬柆宥呯？闁圭増婢樼粈鍫熺箾閸℃ê绔惧ù婊冪秺閺屾稓浠﹂崜褋鈧帡鏌嶇紒妯烩拻闁逞屽墮缁犲秹宕曢柆宥呯疇闁圭増婢樼粻娲煟濡偐甯涢柣?" );
        }

        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("闂傚倸鍊搁崐鎼佸磹閹间礁绠熼柣鎰惈閻愬﹪鏌ㄩ弴妤€浜惧銈冨劜绾板秶鎹㈠☉銏犵闁绘劖娼欑喊宥囩磽娴ｅ壊妲归柟鍛婂▕楠炲啫煤椤忓嫀鈺呮煃鏉炴壆鍔嶇€?" );
        }

        UserTunnel userTunnel = null;
        if (targetStatus == 1) {
            if (tunnel.getStatus() != 1) {
                return R.err("闂傚倸鍊搁崐鎼佸磹閹间礁绠熼柣鎰惈閻愬﹪鏌ㄩ弴妤€浜惧銈冨劜瀹€鎼佸箖濡ゅ啯鍠嗛柛鏇ㄥ幗閹叉瑩鏌ｈ箛鎾剁闁荤啿鏅犳俊鎾川鐎涙ɑ娅囬梺绋挎湰缁嬫垶绂掗崫鍕垫富闁靛牆楠搁獮妤呮偨椤栨粌浠х紒顔肩墛瀵板嫰骞囬鐘插箰闂備礁鎲￠崝锔界濠婂嫮澧＄紓鍌欒兌閾忓酣宕㈡總鍛婂亯濠靛倸鎽滃畵浣搞€掑锝呬壕婵犳鍠掗崑鎾绘⒑缂佹鎲块柛瀣尰娣囧﹪骞撻幒婵堝悑闂佸搫鐭夌换婵嗙暦閻撳簶鏀介柛鈺勶骏閸ㄥ綊鍩?" );
            }
            if (currentUser.getRoleId() != 0) {
                R flowCheckResult = checkUserFlowLimits(currentUser.getUserId(), tunnel);
                if (flowCheckResult.getCode() != 0) {
                    return flowCheckResult;
                }
                userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
                if (userTunnel == null) {
                    return R.err("濠电姷鏁搁崑鐘诲箵椤忓棗绶ら柟绋垮閸欏繘鏌ｉ姀鐘冲暈闁稿骸顦甸弻娑樷槈濞嗘劗绋囬柣搴㈣壘椤︻垶鈥︾捄銊﹀磯闁告繂瀚Σ浼存⒑閹肩偛鈧洟藝闂堟侗娼栧┑鐘宠壘瀹告繈鏌涢妷鎴濆閻撴垶绻濋姀锝庢綈婵炶尙鍠栧濠氬焺閸愩劎绐炴繝鐢靛Т閸婄懓鈻撶拠娴嬫斀?" );
                }
                if (userTunnel.getStatus() != 1) {
                    return R.err("闂傚倸鍊搁崐鎼佸磹閹间礁绠熼柣鎰惈閻愬﹪鏌ㄩ弴妤€浜惧銈冨劜瀹€鎼佸蓟閿濆鍊烽柡澶嬪灥濮ｅ牓姊虹粙娆惧剰婵☆偅绻堟俊鎾川鐎涙ɑ娅囬梺绋挎湰缁嬫垶绂?" );
                }
            }
        }
        if (currentUser.getRoleId() != 0 && userTunnel == null) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("濠电姷鏁搁崑鐘诲箵椤忓棗绶ら柟绋垮閸欏繘鏌ｉ姀鐘冲暈闁稿骸顦甸弻娑樷槈濞嗘劗绋囬柣搴㈣壘椤︻垶鈥︾捄銊﹀磯闁告繂瀚Σ浼存⒑閹肩偛鈧洟藝闂堟侗娼栧┑鐘宠壘瀹告繈鏌涢妷鎴濆閻撴垶绻濋姀锝庢綈婵炶尙鍠栧濠氬焺閸愩劎绐炴繝鐢靛Т閸婄懓鈻撶拠娴嬫斀?" );
            }
        }

        if (userTunnel == null) {
            userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        }

        List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId()).eq("chain_type", 1));

        for (ChainTunnel chainTunnel : chainTunnels) {
            String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
            Node node = nodeService.getById(chainTunnel.getNodeId());
            if (node == null) {
                return R.err("闂傚倸鍊搁崐椋庢閿熺姴绐楅柟鎹愵嚙缁€澶屸偓鍏夊亾闁告洦鍋勯悗顓㈡煙閸忚偐鏆橀柛鏂跨Ч瀹曢潧顓兼径瀣幈闁诲繒鍋涙晶浠嬫儗婵犲伅褰掓偑閳ь剟宕规导鏉戠叀濠㈣埖鍔曠粻鑽も偓瑙勬礀濞夛箓濮€閵堝棛鍘介柟鐑樺▕瀹曟繈骞嬪┑鍫熸?" );
            }
            GostDto gostDto = GostUtil.PauseAndResumeService(node.getId(), serviceName, gostMethod);
            if (!Objects.equals(gostDto.getMsg(), "OK")) return R.err(gostDto.getMsg());
        }
        forward.setStatus(targetStatus);
        forward.setUpdatedTime(System.currentTimeMillis());
        this.updateById(forward);
        return R.ok();
    }

    private String extractIpFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }

        address = address.trim();

        // IPv6闂傚倸鍊风粈渚€骞栭銈囩煋闁割偅娲栭崒銊ф喐韫囨拹? [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1) {
                return address.substring(1, closeBracket);
            }
        }

        // IPv4闂傚倸鍊烽懗鍫曞箠閹剧粯鍋ら柕濞炬櫆閸嬪鏌￠崶銉ョ仾闁稿鍊圭换娑㈠幢濡纰嶉柣鐔哥懕婵″洭鍩€椤掆偓缁犲秹宕曢崡鐐嶆稑鈽夊▎鎴犲骄闂佸綊鍋婇崰鎺楀磻? ip:port 闂?domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0) {
            return address.substring(0, lastColon);
        }

        // 濠电姷鏁告慨鐑姐€傛禒瀣劦妞ゆ巻鍋撻柛鐔锋健閸┾偓妞ゆ巻鍋撶紓宥咃躬楠炲啫螣鐠囪尙绐炴繝鐢靛仧閸嬫捇鎮￠姀銈嗏拺闁哄倶鍎插▍鍛存煕閻曚礁鐏ｇ紒顔碱煼楠炴帡寮崒婊愮床闂備礁鎼崯顖炲垂閻㈠憡鍊堕柣妯虹仛閸犳劙鏌ｅΔ鈧悧蹇撁洪幘顔界厸濞达絽鎽滃瓭闂佷紮绲剧换鍫ュ春閳ь剚銇勯幒鍡椾壕闁绘挶鍊濋弻娑滎槼妞ゃ劌鎳愮划濠氬箮閼恒儳鍘繝銏ｆ硾閻楀棙淇婄捄銊х＜闁绘ê纾崣鈧梺鍝勬湰閻╊垱淇婇悿顖ｆЩ婵炲濮电划宥囨崲濠靛鐒垫い鎺戝缁犵粯銇勯弬瑁ゅ仮闁轰焦绮撳?
        return address;
    }

    private int extractPortFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return -1;
        }

        address = address.trim();

        // IPv6闂傚倸鍊风粈渚€骞栭銈囩煋闁割偅娲栭崒銊ф喐韫囨拹? [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1 && closeBracket + 1 < address.length() && address.charAt(closeBracket + 1) == ':') {
                String portStr = address.substring(closeBracket + 2);
                try {
                    return Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }

        // IPv4闂傚倸鍊烽懗鍫曞箠閹剧粯鍋ら柕濞炬櫆閸嬪鏌￠崶銉ョ仾闁稿鍊圭换娑㈠幢濡纰嶉柣鐔哥懕婵″洭鍩€椤掆偓缁犲秹宕曢崡鐐嶆稑鈽夊▎鎴犲骄闂佸綊鍋婇崰鎺楀磻? ip:port 闂?domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0 && lastColon + 1 < address.length()) {
            String portStr = address.substring(lastColon + 1);
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        // 濠电姷鏁告慨鐑姐€傛禒瀣劦妞ゆ巻鍋撻柛鐔锋健閸┾偓妞ゆ巻鍋撶紓宥咃躬楠炲啫螣鐠囪尙绐炴繝鐢靛仧閸嬫捇鎮￠姀銈嗏拺闁哄倶鍎插▍鍛存煕閻曚礁鐏ｇ紒顔碱煼楠炴帡寮崒婊愮床闂備礁鎼崯顖炲垂閻㈠憡鍊堕柣妯虹仛閸犳劙鏌ｅΔ鈧悧蹇撁洪幘顔界厸濞达絽鎽滃瓭闂佷紮绲剧换鍫濈暦濮椻偓椤㈡棃宕遍埡浣规毉闂?1闂傚倷娴囧畷鐢稿磻閻愮數鐭欓煫鍥ㄧ☉缁€澶愬箹濞ｎ剙濡煎鍛攽椤旂瓔鐒炬繛澶嬬〒閻氭儳顓兼径瀣幗濠碘槅鍨靛▍锝夋晬瀹ュ鐓忛柛鈩冾殣闊剟鏌熼鐓庢Щ闁宠楠搁埢搴ㄥ箣閻樻劖鍨垮?
        return -1;
    }

    private DiagnosisResult performTcpPingDiagnosis(Node node, String targetIp, int port, String description) {
        try {
            // 闂傚倸鍊风粈渚€骞栭锔绘晞闁告侗鍨崑鎾愁潩閻撳骸顫紓浣介哺閹瑰洭鐛幘缁樺€婚柣?ping闂傚倷娴囧畷鍨叏閺夋嚚娲敇閵忕姷鍝楅梻渚囧墮缁夌敻宕曢幋婢濆綊宕楅崗鑲╃▏闂佸搫顑勭欢姘跺蓟閿濆憘鏃堝焵椤掑嫭鍋嬮柛鈩冪懅閻?
            JSONObject tcpPingData = new JSONObject();
            tcpPingData.put("ip", targetIp);
            tcpPingData.put("port", port);
            tcpPingData.put("count", 4);
            tcpPingData.put("timeout", 5000); // 5缂傚倸鍊搁崐椋庣矆娓氣偓钘濋柟娈垮枟閺嗘粓鏌ｉ弮鍥仩闁活厽鎹囬弻鐔虹磼閵忕姵鐏堢紓?
            // 闂傚倸鍊风粈渚€骞夐敓鐘冲仭闁挎洖鍊搁崹鍌炴煕瑜庨〃鍛存倿閸偁浜滈柟杈剧稻绾爼鏌ｉ敐鍥ㄥ发P ping闂傚倸鍊风粈渚€骞夐敍鍕煓闁圭儤顨呴崹鍌涚節闂堟侗鍎忕紒鐙€鍣ｉ弻鏇㈠醇濠垫劖笑缂備讲鍋撻柛鈩冪⊕閻撴瑧鈧懓瀚刊鐣屸偓姘嵆閹藉爼鏁撻悩鏂ユ嫼?
            GostDto gostResult = WebSocketServer.send_msg(node.getId(), tcpPingData, "TcpPing");

            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setTimestamp(System.currentTimeMillis());

            if (gostResult != null && "OK".equals(gostResult.getMsg())) {
                // 闂傚倷娴囬褏鎹㈤幇顔藉床闁瑰濮靛畷鏌ユ煕閳╁啰鈯曢柛搴★攻閵囧嫰寮介妸褏鐓侀梺鍝ュТ濡繈寮婚妸銉㈡斀闁糕剝锕╁Λ銈夋⒑闁偛鑻晶鎵磼娴ｄ警鏆汸 ping闂傚倸鍊风粈渚€骞夐敍鍕床闁稿本绮庨惌鎾绘倵閸偆鎽冨┑顔藉▕閺岋紕浠︾拠鎻掑闂佸搫顑勭欢姘跺蓟閿濆憘鏃堝焵椤掑嫭鍋嬮柛鈩冪懅閻?
                try {
                    if (gostResult.getData() != null) {
                        JSONObject tcpPingResponse = (JSONObject) gostResult.getData();
                        boolean success = tcpPingResponse.getBooleanValue("success");

                        result.setSuccess(success);
                        if (success) {
                            result.setMessage("TCP闂傚倷绀侀幖顐λ囬锕€鐤炬繝闈涱儏绾惧鏌ｉ幇顒備粵闁哄棙绮撻弻鐔虹磼閵忕姵鐏堢紓浣哄缂嶄線寮婚悢鍏肩劷闁挎洍鍋撳褜鍠楅妵?" );
                            result.setAverageTime(tcpPingResponse.getDoubleValue("averageTime"));
                            result.setPacketLoss(tcpPingResponse.getDoubleValue("packetLoss"));
                        } else {
                            result.setMessage(tcpPingResponse.getString("errorMessage"));
                            result.setAverageTime(-1.0);
                            result.setPacketLoss(100.0);
                        }
                    } else {
                        // 婵犵數濮烽弫鎼佸磻濞戞瑥绶為柛銉墮缁€鍫熺節闂堟稒锛旈柤鏉跨仢闇夐柨婵嗘搐閸斿鎮楀顓炲摵闁哄被鍔戝顒勫垂椤旇瀵栭梻浣规た閸樺ジ鏌婇敐澶婅摕闁斥晛鍟刊鎾煕濠靛棗顏撮柡鍡╁弮閹鎲撮崟顒€鐭紓浣藉煐瀹€绋款嚕椤愩埄鍚嬮柛銉檮閸曞啴姊虹紒妯诲碍缂併劌鐖煎姝岀疀濞戞瑢鎷哄┑鐐跺皺缁垱绻涢崶顒佺厱闁哄啠鍋撻柛銊ョ秺瀹曟岸骞掑Δ濠冩櫖濠电姴锕ょ€氼剟顢撳☉銏♀拺闂傚牊绋撴晶鏇㈡煙閸愬樊妯€鐎?                        result.setSuccess(true);
                        result.setMessage("TCP闂傚倷绀侀幖顐λ囬锕€鐤炬繝闈涱儏绾惧鏌ｉ幇顒備粵闁哄棙绮撻弻鐔虹磼閵忕姵鐏堢紓浣哄缂嶄線寮婚悢鍏肩劷闁挎洍鍋撳褜鍠楅妵?" );
                        result.setAverageTime(0.0);
                        result.setPacketLoss(0.0);
                    }
                } catch (Exception e) {
                    // 闂傚倷娴囧畷鐢稿窗閹扮増鍋￠弶鍫氭櫅缁躲倕螖閿濆懎鏆為柛濠囨涧闇夐柣妯烘▕閸庡繐霉閼测晛啸闁逞屽墮缁犲秹宕曢柆宥呯疇闁归偊鍠掗崑鎾绘偡闁附鈻堥梺鍝勫閸撴繈骞忛崨鏉戜紶闁告洖鐏氬В澶愭煟鎼淬値娼愰柟鍝ュ厴閹偤鏁冮埀顒勵敋閵夆晛绀嬫い鎺嶈兌缁夎泛顪冮妶鍡楀闁稿孩鐩鎾閿涘嫬寮虫繝鐢靛仦閸ㄥ爼鏁嬪銈冨妽閻熝呮閹烘鍤戞い鎺嗗亾濠殿喓鈧椂 ping闂傚倸鍊风粈渚€骞夐敍鍕煓闁圭儤顨呴崹鍌涚節闂堟侗鍎忕紒鐙€鍣ｉ弻鏇㈠醇濠垫劖笑闁诲孩鑹鹃ˇ浼村Φ閸曨垰绠抽柟瀛樼箖濞堝姊虹紒妯诲皑闁哄懐濞€瀵鎮㈢悰鈥充壕婵炴垶顏鍛弿闁搞儜鈧弨鑺ャ亜閺冨倹娅曢柍顖涙礈缁?                    result.setSuccess(true);
                    result.setMessage("TCP闂傚倷绀侀幖顐λ囬锕€鐤炬繝闈涱儏绾惧鏌ｉ幇顒備粵闁哄棙绮撻弻鐔虹磼閵忕姵鐏堢紓浣哄缂嶄線寮婚悢鍏肩劷闁挎洍鍋撳褜鍠楅妵鍕敃閵堝應鏋呴梺鍝勮閸斿矂鍩ユ径濞㈢喐绗熼娑卞敳缂傚倸鍊烽悞锕傛偂閿涘嫮涓嶉柡宓苯娈ㄥ銈嗘磵閸嬫捇鏌熼瑙勬珔闁伙絿鍏樺畷鍫曞Ψ閵忕姳澹曟繝鐢靛Т濞诧箓宕戦埄鍐闁糕剝顭囬幊鍛磼鐎ｎ亶鐓奸柡宀嬬稻閹棃鏁愰崱妯煎涧闂備礁缍婇ˉ鎾寸箾閳ь剟鏌℃担绋库偓鍨暦濠婂棭妲鹃梺缁樻尭鐎氭澘顫忛悜妯诲闁瑰嘲鑻崢鈩冪節閳封偓閸曞灚鐣堕梺?" );
                    result.setAverageTime(0.0);
                    result.setPacketLoss(0.0);
                }
            } else {
                result.setSuccess(false);
                result.setMessage(gostResult != null ? gostResult.getMsg() : "闂傚倸鍊烽懗鍫曞储瑜旈獮鏍敃閿曗偓绾剧懓鈹戦悩瀹犲缁炬儳顭烽弻銊モ攽閸♀晜笑缂備緡鍋勭粔褰掑蓟閿濆憘鐔煎传閸曨參鐛撻梻浣筋嚃閸ㄩ亶鎮烽埡鍛祦?" );
                result.setAverageTime(-1.0);
                result.setPacketLoss(100.0);
            }

            return result;
        } catch (Exception e) {
            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setSuccess(false);
            result.setMessage("闂傚倷娴囧畷鍨叏閺夋嚚娲Χ婢跺娅囬梺闈涱槴閺呮盯鎷戦悢鍏肩厱闁靛鍠栨晶顔剧磼閻橆喖鍔ら柟鍙夋倐楠炲鏁傜悰鈥充壕濞撴埃鍋撴鐐差儏閳规垿骞囬渚囧悪闂傚倷鑳堕…鍫ュ嫉椤掆偓椤繈濡搁埡浣规К? " + e.getMessage());
            result.setTimestamp(System.currentTimeMillis());
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
    }

    private DiagnosisResult performTcpPingDiagnosisWithConnectionCheck(Node node, String targetIp, int port, String description) {
        DiagnosisResult result = new DiagnosisResult();
        result.setNodeId(node.getId());
        result.setNodeName(node.getName());
        result.setTargetIp(targetIp);
        result.setTargetPort(port);
        result.setDescription(description);
        result.setTimestamp(System.currentTimeMillis());

        try {
            return performTcpPingDiagnosis(node, targetIp, port, description);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("闂傚倷绀侀幖顐λ囬锕€鐤炬繝闈涱儏绾惧鏌ｉ幇顒備粵闁哄棙绮撻弻鈩冨緞鐎ｎ亝顔曞┑鐐村灟閸ㄥ綊鎮為崹顐犱簻闁圭儤鍨甸鈺呮煢閸愵亜鏋涢柡灞炬礃瀵板嫰宕煎┑鍡椥戠紓浣瑰劤瑜扮偟鍒掑▎鎾崇畺? " + e.getMessage());
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
    }

    private UserInfo getCurrentUserInfo() {
        Integer userId = JwtUtil.getUserIdFromToken();
        Integer roleId = JwtUtil.getRoleIdFromToken();
        String userName = JwtUtil.getNameFromToken();
        return new UserInfo(userId, roleId, userName);
    }

    private Tunnel validateTunnel(Integer tunnelId) {
        return tunnelService.getById(tunnelId);
    }

    private Forward validateForwardExists(Long forwardId, UserInfo currentUser) {
        Forward forward = this.getById(forwardId);
        if (forward == null) {
            return null;
        }

        // 闂傚倸鍊风粈渚€骞栭锕€绠悗锝庡枛闂傤垶鏌ㄥ┑鍡╂Ц闁绘帒鐏氶妵鍕箳瀹ュ洤濡介梺姹囧妽閸ㄥ潡寮婚埄鍐╁闂傚牊绋堥崑鎾斥攽鐎ｎ剙绁﹂柣搴秵娴滅偤寮崇€ｎ喗鐓涘璺侯儏椤曟粓姊哄▎鎯у籍婵﹥妞介幃鈩冩償閿濆棛鈧喗绻濆▓鍨灁闁稿﹥绻堥悰顕€寮介鐔哄弳闁诲函缍嗛崑鍕敁閹炬枼鏀介柣妯款嚋瀹搞儲淇婇锝庢當闁崇粯鎹囬獮宥夘敊閻熼澹曢柣鐔哥懃鐎氼噣骞楅崘顭嬬懓顭ㄦ惔鈥愁潻闂佸磭绮幑鍥嵁鐎ｎ喗鍊烽柡澶嬪焾濞碱垱淇婇悙顏勨偓鏍ь啅婵犳艾纾婚柟鎹愬煐閸?
        if (currentUser.getRoleId() != 0 &&
                !Objects.equals(currentUser.getUserId(), forward.getUserId())) {
            return null;
        }

        return forward;
    }

    private UserPermissionResult checkUserPermissions(UserInfo currentUser, Tunnel tunnel, Long excludeForwardId) {
        if (currentUser.getRoleId() == 0) {
            return UserPermissionResult.success(null, null);
        }

        // 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ椤旂厧顫梺绋款儍閸旀垿寮婚弴鐔虹瘈闊洦娲滈弳鐘绘⒑缂佹ɑ灏版繛鍙夌墳瑜颁線姊洪幖鐐插姷缂佽尪濮ら弲鍫曞箵閹?
        User userInfo = userService.getById(currentUser.getUserId());
        if (userInfo.getExpTime() != null && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("闂備浇宕甸崰鎰垝鎼淬垺娅犳俊銈呮噹缁犱即鏌涘☉姗堟敾婵炲懐濞€閺岋絽螣閾忛€涚驳闁诲孩鑹鹃…鐑藉蓟閵娾晛鍗虫俊銈傚亾濞存粓绠栭幃宄邦煥閸曨剛鍑￠梺璇茬箲缁诲牓銆佸▎鎺旂杸婵炴垶顭囬宀勬⒑閸︻厼鍔嬮柛銊ョ秺瀹曘垽骞掑Δ浣叉嫼?" );
        }

        // 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘嚩鎴︽⒒娴ｈ姤銆冮梻鍕Ч瀹曟劕鈹戠€ｎ剙绁﹂柣搴秵閸犳宕愭繝姘厾闁诡厽甯掗崝銈嗕繆閹绘帗鍠樻慨濠勭帛缁楃喖宕惰椤亪姊虹憴鍕憙妞ゆ泦鍥ｂ偓锕傚炊椤掆偓闁卞洭鏌曟径娑滃悅闁?
        UserTunnel userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
        if (userTunnel == null) {
            return UserPermissionResult.error("濠电姷鏁搁崑鐘诲箵椤忓棗绶ら柟绋垮閸欏繘鏌ｉ姀鐘冲暈闁稿骸顦甸弻娑樷槈濞嗘劗绋囬柣搴㈣壘椤︻垶鈥︾捄銊﹀磯闁告繂瀚Σ浼存⒑閹肩偛鈧洟藝闂堟侗娼栧┑鐘宠壘瀹告繈鏌涢妷鎴濆閻撴垶绻濋姀锝庢綈婵炶尙鍠栧濠氬焺閸愩劎绐炴繝鐢靛Т閸婄懓鈻撶拠娴嬫斀?" );
        }

        if (userTunnel.getStatus() != 1) {
            return UserPermissionResult.error("闂傚倸鍊搁崐鎼佸磹閹间礁绠熼柣鎰惈閻愬﹪鏌ㄩ弴妤€浜惧銈冨劜瀹€鎼佸蓟閿濆鍊烽柡澶嬪灥濮ｅ牓姊虹粙娆惧剰婵☆偅绻堟俊鎾川鐎涙ɑ娅囬梺绋挎湰缁嬫垶绂?" );
        }

        // 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘嚩鏇熺節濞堝灝娅欑紒鍙夋そ瀹曟垿骞樼紒妯锋嫽婵炴挻鑹惧ú銈嗘櫠椤栫偞鐓欐慨婵嗙焾閸ゆ瑦銇勯鐐村枠鐎规洖銈告俊鐑芥晜鐟欏嫬鐦遍梻鍌欑缂嶅﹪宕戞繝鍥х婵炲棙鎸婚崑鍌炴煕瀹€鈧崑鐐烘偂濞戙垺鐓曟い鎰Т閻忊晠鏌＄€ｎ偄鐏﹀ǎ鍥э躬閹瑩骞撻幒鍡椾壕闁割煈鍣?
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("闂傚倷娴囧畷鍨叏閺夋嚚娲閵堝懐锛熼梻鍌氱墛閸掆偓闁圭儤顨呴悞鍨亜閹烘垵顏柍閿嬪灦閵囧嫰骞掑鍥у闁诲繐绻掓晶妤冩崲濞戙垺鍋傞幖杈剧到閳潧鈹戦纭锋缂侇喗鐟╅悰顕€宕堕澶嬫櫌闂侀€炲苯澧寸€规洜鏁婚獮鎺懳旀担鍝勫箞闂備礁婀遍崕銈夊蓟閵娾斂鈧倿宕楅懖鈺冪槇闂?" );
        }

        // 婵犵數濮烽弫鎼佸磻閻旂儤宕叉繝闈涚墛椤愯姤鎱ㄥΟ鎸庣【闂傚偆鍨辩换娑橆啅椤旇崵鍑归梺鎶芥敱濡啴寮婚悢鍏肩劷闁挎洍鍋撳褜鍣ｉ弻锝夊箻閺夋垹浠告繛锝呮搐閿曨亪銆侀弴銏″亜闁炬艾鍊搁ˉ姘舵⒒?
        if (userInfo.getFlow() <= 0) {
            return UserPermissionResult.error("闂傚倸鍊烽悞锕€顪冮崹顕呯劷闁秆勵殔缁€澶屸偓骞垮劚椤︻垶寮伴妷锔剧闁瑰瓨鐟ラ悘鈺冪磼閻樺啿鐏╃紒杈ㄦ尰閹峰懐鎷犻敍鍕Ш闂備線娼ч悧鐐电不閹炬剚娼栨繛宸簻缁犵敻鏌熼悜妯烩拻妞ゆ柨娲娲川婵犲倻鍘愮紓浣虹帛缁诲牓鐛崘鈺冾浄閻庯綆鈧厸鏅犻弻鏇熺珶椤栨艾顏い?" );
        }
        if (userTunnel.getFlow() <= 0) {
            return UserPermissionResult.error("闂傚倷娴囧畷鍨叏閺夋嚚娲閵堝懐锛熼梻鍌氱墛閸掆偓闁圭儤顨呴悞鍨亜閹烘垵顏柍閿嬪灦閵囧嫰骞掑鍥у闁诲繐绻戞竟鍡欐閹烘挾鐟瑰┑鐘插閺嗐倝姊洪崫鍕闁活厺鑳剁划璇测槈閵忕姷顔掗梺鍛婂姇瀵爼顢樻總鍛娾拻濞达綀顫夐妵鐔哥箾閻撳孩鍋ョ€规洘绻傞悾婵嬪礋椤掆偓娴?" );
        }

        // 闂傚倷绀侀幖顐λ囬柆宥呯？闁圭増婢樼粈鍫熺箾閸℃ê绔惧ù婊冪秺閺屾稑鐣濋埀顒勫磻濞戙垹鍑犲ù锝堫嚉瑜版帒绀傞柛蹇氬亹缁嬪洤鈹戦悙鍙夊櫡闁稿﹥绻傞～蹇旂節濮橆剛顦板銈嗗坊閸嬫挸鈹戦鍏煎枠闁哄本鐩崺锟犲磼閵堝棛鏆ラ梺鎹愬吹閸嬨倝寮诲鍫闂佸憡鎸鹃崰搴敋?
        R quotaCheckResult = checkForwardQuota(currentUser.getUserId(), tunnel.getId().intValue(), userTunnel, userInfo, excludeForwardId);
        if (quotaCheckResult.getCode() != 0) {
            return UserPermissionResult.error(quotaCheckResult.getMsg());
        }

        return UserPermissionResult.success(userTunnel.getSpeedId(), userTunnel);
    }

    private R checkForwardQuota(Integer userId, Integer tunnelId, UserTunnel userTunnel, User userInfo, Long excludeForwardId) {
        // 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘嚩鎴︽⒒娴ｈ姤銆冮梻鍕Ч瀹曟劕鈹戠€ｎ剙绁﹂柣搴秵閸犳鎮￠妷鈺傚€甸柨娑樺船閸熲晜绔熼弴銏♀拺闁告繂瀚鈺冪磼缂佹ê鐏╃紒顔硷躬椤㈡岸鍩€椤掑嫬钃熸繛鎴炃氶弸搴ㄦ煙闁箑澧板ù鐘虫倐濮婃椽宕崟顒夋￥闂佸摜濮甸悧鐘差嚕鐠囪尙鏆嗛柛鎰典簽閺夌鈹戦悙鏉戠仸闁荤噥鍨堕崺鈧い鎺嶈兌閹冲洭鏌?
        long userForwardCount = this.count(new QueryWrapper<Forward>().eq("user_id", userId));
        if (userForwardCount >= userInfo.getNum()) {
            return R.err("Forward quota exceeded. Limit: " + userInfo.getNum());
        }

        // 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘嚩鎴︽⒒娴ｈ姤銆冮梻鍕Ч瀹曟劕鈹戠€ｎ剙绁﹂柣搴秵娴滅偤寮崇€ｎ喗鐓熸俊銈傚亾闁绘鍔欏畷銉╊敃閿旂晫鍘介梺缁樏鍫曞磼閵娾晜鐓曢幖娣妽濞懷呮偖濞嗘挻鐓冪憸婊堝礈閻斿娼栨繛宸憾閺佸棗霉閿濆洦鍤€闁哄棙顨呴埞鎴︽倷閼碱剙顤€闂佹悶鍔忓▔娑⑩€﹂崶顒夋晜闁割偒鍋呴弲銏ゆ⒑闁偛鑻晶鏌ユ煏閸ャ劌濮嶇€规洘甯￠幃娆撳矗婢跺备鍋撻浣虹闁哄鍨甸幃鎴炵箾閸忚偐鎳冮柍璇茬Ч婵¤埖寰勭€Ｑ勫闂備礁鎲″ú锕傚礈濞嗘劖鍙忛柛銉墯閻?
        QueryWrapper<Forward> tunnelQuery = new QueryWrapper<Forward>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId);

        if (excludeForwardId != null) {
            tunnelQuery.ne("id", excludeForwardId);
        }

        long tunnelForwardCount = this.count(tunnelQuery);
        if (tunnelForwardCount >= userTunnel.getNum()) {
            return R.err("Tunnel forward quota exceeded. Limit: " + userTunnel.getNum());
        }

        return R.ok();
    }

    private R checkUserFlowLimits(Integer userId, Tunnel tunnel) {
        User userInfo = userService.getById(userId);
        if (userInfo.getExpTime() != null && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return R.err("闂備浇宕甸崰鎰垝鎼淬垺娅犳俊銈呮噹缁犱即鏌涘☉姗堟敾婵炲懐濞€閺岋絽螣閾忛€涚驳闁诲孩鑹鹃…鐑藉蓟閵娾晛鍗虫俊銈傚亾濞存粓绠栭幃宄邦煥閸曨剛鍑￠梺璇茬箲缁诲牓銆佸▎鎺旂杸婵炴垶顭囬宀勬⒑閸︻厼鍔嬮柛銊ョ秺瀹曘垽骞掑Δ浣叉嫼?" );
        }

        UserTunnel userTunnel = getUserTunnel(userId, tunnel.getId().intValue());
        if (userTunnel == null) {
            return R.err("濠电姷鏁搁崑鐘诲箵椤忓棗绶ら柟绋垮閸欏繘鏌ｉ姀鐘冲暈闁稿骸顦甸弻娑樷槈濞嗘劗绋囬柣搴㈣壘椤︻垶鈥︾捄銊﹀磯闁告繂瀚Σ浼存⒑閹肩偛鈧洟藝闂堟侗娼栧┑鐘宠壘瀹告繈鏌涢妷鎴濆閻撴垶绻濋姀锝庢綈婵炶尙鍠栧濠氬焺閸愩劎绐炴繝鐢靛Т閸婄懓鈻撶拠娴嬫斀?" );
        }

        // 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘嚩鏇熺節濞堝灝娅欑紒鍙夋そ瀹曟垿骞樼紒妯锋嫽婵炴挻鑹惧ú銈嗘櫠椤栫偞鐓欐慨婵嗙焾閸ゆ瑦銇勯鐐村枠鐎规洖銈告俊鐑芥晜鐟欏嫬鐦遍梻鍌欑缂嶅﹪宕戞繝鍥х婵炲棙鎸婚崑鍌炴煕瀹€鈧崑鐐烘偂濞戙垺鐓曟い鎰Т閻忊晠鏌＄€ｎ偄鐏﹀ǎ鍥э躬閹瑩骞撻幒鍡椾壕闁割煈鍣?
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return R.err("闂傚倷娴囧畷鍨叏閺夋嚚娲閵堝懐锛熼梻鍌氱墛閸掆偓闁圭儤顨呴悞鍨亜閹烘垵顏柍閿嬪灦閵囧嫰骞掑鍥у闁诲繐绻掓晶妤冩崲濞戙垺鍋傞幖杈剧到閳潧鈹戦纭锋缂侇喗鐟╅悰顕€宕堕澶嬫櫌闂侀€炲苯澧寸€规洜鏁婚獮鎺懳旀担鍝勫箞闂備礁婀遍崕銈夊蓟閵娾斂鈧倿宕楅懖鈺冪槇闂侀€炲苯澧撮柡浣规崌閹晠鎼归锝囧建闂備浇顕х换鎺楀磻閻旂厧纾婚柟鍓х帛閺呮悂鏌曟径娑橆洭缂佺姵鍎抽…璺ㄦ崉娓氼垰鍓辩紓浣插亾闁告劦鐓佽ぐ鎺撳亼闁逞屽墴瀹曟澘螖閳ь剟顢氶妷鈺佺妞ゆ劦鍋勯幃鎴︽⒑缁洖澧叉い顓炴喘閹潡鍩€椤掑嫭鈷?" );
        }

        // 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘嚩鎴︽⒒娴ｈ姤銆冮梻鍕Ч瀹曟劕鈹戠€ｎ剙绁﹂柣搴秵閸犳鎮￠妷鈺傚€甸柨娑樺船閸熲晜绔熼弴鐘电＝闁稿本鐟ч崝宥嗐亜椤撶偞鍠樺┑鈥崇埣閺佸啴宕掑☉鎺撳闂備礁婀辩划顖滄暜閻愮數涓嶇紓浣姑肩换鍡涙煟閹伴潧澧柕鍥ㄧ箘閳?
        if (userInfo.getFlow() * BYTES_TO_GB <= userInfo.getInFlow() + userInfo.getOutFlow()) {
            return R.err("闂傚倸鍊烽悞锕€顪冮崹顕呯劷闁秆勵殔缁€澶屸偓骞垮劚椤︻垶寮伴妷锔剧闁瑰瓨鐟ラ悘鈺冪磼閻樺啿鐏╃紒杈ㄦ尰閹峰懐鎷犻敍鍕Ш闂備線娼ч悧鐐电不閹炬剚娼栨繛宸簻缁犵敻鏌熼悜妯烩拻妞ゆ柨娲娲川婵犲倻鍘愮紓浣虹帛缁诲牓鐛崘鈺冾浄閻庯綆鈧厸鏅犻弻鏇熺珶椤栨艾顏い鏂挎嚇濮婄粯鎷呴悷閭﹀殝缂備礁顑嗛崹鍨嚕閻㈠壊鏁嗛柍褜鍓濋悘瀣⒑闂堟稓澧曢柟鍐差樀瀵悂寮介鐔哄幐闂佹悶鍎弲娑㈠几瀹ュ鐓曢柟鐑樻尭缁楁帗銇勯鈩冪《闁圭懓瀚伴幖褰掑礈閹板墎绋荤紒缁樼箞閸┾偓妞ゆ帒瀚悙濠冦亜閹哄棗浜剧紓浣插亾?" );
        }

        // 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘嚩鏇熺節濞堝灝娅欑紒鍙夋そ瀹曟垿骞樼紒妯锋嫽婵炴挻鑹惧ú銈嗘櫠椤栫偞鐓欐慨婵嗚嫰閸氬湱绱掗纭疯含闁轰礁鍟村畷鎺戔槈濮橆剙绗撻梻鍌氼煬閸嬪嫬煤閿斿墽鐭欓柟鐑橆殢閺佷焦绻涢崱妯诲鞍闁?        // 闂傚倸鍊峰ù鍥ь浖閵娾晜鍤勯柤绋跨仛濞呯姵淇婇妶鍌氫壕闂佷紮绲介悘姘跺箯閸涱垱鍠嗛柛鏇ㄥ亜婵℃娊姊绘担绛嬫綈鐎规洘顭囨禍鎼侇敂閸繂鍋嶅銈嗗笒鐎氼參鎮￠悢鍏肩厪濠电偛鐏濋崝姘亜韫囷絼绨界紒杈ㄥ笚缁楃喐绻濋崟顓熸闂備礁鎼懟顖炴儗娴ｈ櫣涓嶆繛鎴欏灩缁犲鏌涢幇顒€绾фい鎾炽偢濮婄粯鎷呴崨濠呯濡炪値鍘奸悧鎾规闂佺懓澧庨悺鏃堝极閸ヮ剚鐓曟繝闈涘閸旀粓鎮楀顒夌吋闁哄本鐩弫鍌滄嫚閹绘帞鍝楁俊鐐€ら崑鎾剁不閹捐钃熸繛鎴欏灩鎯熼梺闈涱樈閸犳牠宕滈弶娆炬富闁靛牆绻楅崥鍌炴煛閸涱垰鈻堟鐐插暣瀹曞ジ寮撮悙鑼垛偓鍨渻閵堝棙灏靛┑顔惧厴钘濇い鏇楀亾婵﹥妞介幃婊堝煛閸屾稓褰呴梻浣割吔閺夊灝顬嬮梺杞扮贰閸犳岸鍩€椤掍胶鈯曢拑鍗炩攽椤栨稒灏﹂柟顔肩秺楠炰線骞掗幋婵愮€撮梻浣告惈濡鎹㈠鈧濠氭偄绾拌鲸鏅㈤梺鍛婂姈閸庡啿鈻撻銏♀拺?
        long tunnelFlow = userTunnel.getInFlow() + userTunnel.getOutFlow();

        if (userTunnel.getFlow() * BYTES_TO_GB <= tunnelFlow) {
            return R.err("闂傚倷娴囧畷鍨叏閺夋嚚娲閵堝懐锛熼梻鍌氱墛閸掆偓闁圭儤顨呴悞鍨亜閹烘垵顏柍閿嬪灦閵囧嫰骞掑鍥у闁诲繐绻戞竟鍡欐閹烘挾鐟瑰┑鐘插閺嗐倝姊洪崫鍕闁活厺鑳剁划璇测槈閵忕姷顔掗梺鍛婂姇瀵爼顢樻總鍛娾拻濞达綀顫夐妵鐔哥箾閻撳孩鍋ョ€规洘绻傞悾婵嬪礋椤掆偓娴犵厧顪冮妶鍡楃瑨閻庢凹鍙冨鏌ヮ敆閸曨剙鈧爼鏌ｉ幇鐗堟锭濞存粓绠栭弻銊╂偄閺夋垵濮曠紓浣虹帛缁嬫帒顕ラ崟顓涘亾閿濆骸浜炴い锔哄妽缁绘稓鈧數顭堥宀勬煕閻樻剚娈旀い鏇樺劦瀹曠喖顢曢銏″€梻浣虹《閸撴繈鈥﹂崶顒佸仾闁逞屽墴濮?" );
        }

        return R.ok();
    }

    private UserTunnel getUserTunnel(Integer userId, Integer tunnelId) {
        return userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId));
    }

    private String buildServiceName(Long forwardId, Integer userId, UserTunnel userTunnel) {
        int userTunnelId = (userTunnel != null) ? userTunnel.getId() : 0;
        return forwardId + "_" + userId + "_" + userTunnelId;
    }


    public List<ChainTunnel> get_port(List<ChainTunnel> chainTunnelList, Integer in_port, Long forward_id) {
        List<List<Integer>> list = new ArrayList<>();

        // 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ椤斿彞铏庨梺鍦劋濞叉粓鎮块埀顒勬煙閸忚偐鏆橀柛鏂胯嫰閳绘挸鈹戠€ｎ偀鎷洪梺缁樻尭鐎涒晠骞嗛崼銉︾厱濠电姴鍋嗛悡鑲┾偓瑙勬礃閸ㄥ潡寮幇顓炵窞閻庯綆浜滄竟鍕⒒娴ｅ憡鍟炴繛璇х畵瀹曪絾鎯旈姀鐙€鍤ら梺绋跨灱閸嬬偤鎮￠弴鐔虹闁糕剝顭囬幊鍛存煕閹哄秴宓嗛柡灞剧洴閳ワ箓骞嬪鍛櫦闂?
        for (ChainTunnel tunnel : chainTunnelList) {
            List<Integer> nodePort = getNodePort(tunnel.getNodeId(), forward_id);
            if (nodePort.isEmpty()) {
                throw new RuntimeException("闂傚倸鍊风粈渚€骞栭鈶芥稑螖閸涱厾锛欓梺鑽ゅ枑鐎氬牆鈽夐姀鐘栄冾熆鐠虹尨鍔熼柣鎾村灥椤啴濡堕崱娆忣潷缂備緡鍠栫粔鐟邦嚕鐎圭姷鐤€闁哄洨濮烽敍婊堟⒑閸濆嫬鏆為柛銊ф暬閹﹢鎮╅崹顐㈡瀾?" );
            }
            list.add(nodePort);
        }

        // ========== 濠电姷鏁告慨鐑姐€傛禒瀣劦妞ゆ巻鍋撻柛鐔锋健閸┾偓妞ゆ巻鍋撶紓宥咃躬楠炲啫螣鐠囪尙绐為梺褰掑亰閸撴盯顢欓崶顒佲拺闁硅偐鍋涢崝鈧梺鍛婁緱閸犳鎯侀悙娴嬫斀?in_port闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟瀵稿仧闂勫嫰鏌￠崘銊モ偓褰掞綖閺囩喆浜滈柟鎯у船閻忊晝绱掗埀顒勫磼閻愬鍘卞銈嗗姂閸婃洖鈻嶈箛鏃€鍙忓┑鐘叉川閻ｆ椽鏌″畝瀣？濞寸媴濡囬幏鐘诲箵閹烘埈娼涢梻鍌欑閹诧繝鎮烽敃鈧叅闁绘柨顨庨崵?==========
        if (in_port != null) {
            for (List<Integer> ports : list) {
                if (!ports.contains(in_port)) {
                    throw new RuntimeException("闂傚倸鍊风粈浣革耿闁秮鈧箓宕煎婵囨そ婵¤埖寰勭€ｎ亙鎮ｉ梻浣告啞閹稿棝宕ㄩ鐐缎曞┑锛勫亼閸婃牠鎮уΔ鍛殞闁告挆鍛槸?" + in_port + " 濠电姷鏁搁崑鐐哄垂閸洖绠伴柛婵勫劤閻挾鐥幆褜鍎嶅ù婊冪秺閺岋紕浠︾拠鎻掑闂佺顑冮崝宥夊Φ閸曨垼鏁囬柣鏃堫棑椤戝倻绱撴担鎻掍壕闂佸憡娲﹂崜锕€鈻撴禒瀣厽闁归偊鍨伴惃娲煕閳哄绋荤紒缁樼〒閸犲﹪宕ｅ鈧崑妤€螖閻橀潧浠滅紒澶婄秺瀹曟椽鍩€椤掍降浜滈柟鐑樺灥椤忊晠鎮楀顒夋Ц妞ゎ叀娉曢幑鍕洪宥佸亾濡ゅ懏鎳氶柨鐔哄У閳锋垹绱撴担濮戭亝鎱ㄩ崶鈺冪＝鐎广儱鎷戦煬顒傗偓瑙勬礃椤ㄥ懘锝炲鍫濈劦妞ゆ巻鍋撴い鏇秮瀹曞綊顢曢姀銏㈢嵁闂佽鍑界紞浣割焽瑜庢穱濠囧箥椤斿墽锛濇繛杈剧秬椤藟閺冨牊鐓曢柕濠忕畱椤庢粓鏌曢崶銊ュ闁宠棄顦垫慨鈧柍鈺佸暞閻?" );
                }
            }

            // 闂傚倸鍊风粈浣革耿闁秲鈧倹绂掔€ｎ亞锛涢梺鐟板⒔缁垶鎮″☉銏＄厱妞ゆ劧绲跨粻銉︿繆閼碱剙甯堕柍瑙勫灴閸┿儵宕卞Δ鈧猾宥夋⒑鐠団€虫灍闁荤喆鍔戞俊鐢稿箛閺夎法顔婇梺鐟扮摠缁诲倻绮婚敐澶嬧拻濞达絽鎲￠幆鍫ユ煟濡も偓濡繆妫熼梺鐟板閻℃棃寮€ｎ偅鍙忔俊顖氥仒閸氼偊鏌涘Δ浣侯暡闁靛洤瀚板浠嬪Ω閵娿儺妫熺紓?闂傚倷娴囧畷鍨叏瀹曞洨鐭嗗ù锝堫潐濞呯姴霉閻樺樊鍎愰柛瀣典邯閺屾盯鍩勯崘顏佹闂?ChainTunnel
            for (ChainTunnel tunnel : chainTunnelList) {
                tunnel.setPort(in_port);
            }
            return chainTunnelList;
        }

        // ========== 闂傚倸鍊风粈渚€骞栭锔藉亱婵犲﹤瀚々鍙夌節闂堟稓澧愰柛瀣崌瀹曞綊顢曢敐鍥吇闂?in_port 闂傚倸鍊风粈渚€骞栭銈嗗仏妞ゆ劧绠戠壕鍧楁煕濞嗗浚妲洪柣婵婂煐閹便劌顪冪拠韫闁诲孩顔栭崰姘跺极婵犳艾鏄ラ柍褜鍓氶妵鍕箳閹存績鍋撻悜鑺ュ€垮┑鐘叉川閸欐捇鏌涢妷锝呭缂佲偓閸愵喗鐓熼柨婵嗩槷閹查箖鏌＄仦鍓ь灱缂佺姵鐩獮妯尖偓鍦Т閻撴﹢姊绘担绛嬪殐闁哥姵顨婇獮鎰板礈瑜嶉崹婵嬫煃閸濆嫭濯奸柡浣哥У閹便劌顫滈崱妤€顫紓?==========
        Set<Integer> intersection = new HashSet<>(list.getFirst());
        for (int i = 1; i < list.size(); i++) {
            intersection.retainAll(list.get(i));
        }

        if (!intersection.isEmpty()) {
            // 闂傚倸鍊风粈浣革耿闁秴鏋侀柟闂寸绾惧鏌熼崜褍浠洪柍褜鍓氱敮鈥崇暦濠婂嫭濯村瀣婢ч箖姊绘繝搴′簻婵炶绠戠叅闁挎棁妫勯閬嶆煕瀹€鈧崑鐐烘偂?
            Integer commonMin = intersection.stream().min(Integer::compareTo).orElseThrow();

            // 闂傚倷娴囧畷鍨叏瀹曞洨鐭嗗ù锝堫潐濞呯姴霉閻樺樊鍎愰柛瀣典邯閺屾盯鍩勯崘顏佹缂備讲鍋撻柛鈩冪⊕閻撶喐淇婇妶鍌氫壕闂佺粯顨呭Λ婵嬪Υ閸岀偛钃熼柕澶涘閸樻悂姊洪崜鎻掍簼缂佽绉村嵄闁绘挸绨堕弨浠嬫煃閽樺顥滅€殿噮鍣ｉ弻?
            for (ChainTunnel tunnel : chainTunnelList) {
                tunnel.setPort(commonMin);
            }

            return chainTunnelList;
        }

        // ========== 婵犵數濮烽弫鎼佸磻濞戞瑥绶為柛銉墮缁€鍫熺節闂堟稒锛旈柤鏉跨仢閵嗘帒顫濋敐鍛闁诲氦顫夊ú姗€銆冩繝鍌滄殾闁跨喓濮甸崐鐑芥煛婢跺鐏嶉柛瀣尰濞煎繘鍩￠崘顏庣床闂備礁鎼崯顖炲垂閻㈠憡鍊堕柣妯虹仛閸犳劙鏌ｅΔ鈧悧蹇撁虹€电硶鍋撶憴鍕┛缂傚秳绀侀锝夊磹閻曚焦歇闂備胶顭堥敃銉┿€冩繝鍥ц摕闁靛牆妫涢梽鍕熆鐠轰警妲洪柡浣圭墪椤啴濡堕崱妤冧淮濠电偠顕滄俊鍥╁垝濞嗘劕绶為柟閭﹀墰閸旓箑顪冮妶鍡楃瑐闁煎啿鐖奸崺銏ゅ籍閳ь剟濡甸崟顔剧杸闁圭偓鍓氭禒濂告煟閻樿京鍔嶉柣鎿勭節瀵鏁愭径娑氱◤濡炪倖宸婚崑鎾斥攽閳╁啫顕滈柕鍥у缁犳盯鏁愰崨顓涙嫬闁?==========
        for (int i = 0; i < chainTunnelList.size(); i++) {
            List<Integer> ports = list.get(i);
            Integer first = ports.getFirst();
            chainTunnelList.get(i).setPort(first);
        }

        return chainTunnelList;
    }

    public List<Integer> getNodePort(Long nodeId, Long forward_id) {
        synchronized (NODE_PORT_LOCKS.computeIfAbsent(nodeId, k -> new Object())) {
            Node node = nodeService.getById(nodeId);
            if (node == null) {
                throw new RuntimeException("闂傚倸鍊烽懗鍫曞储瑜旈獮鏍敃閿曗偓绾剧懓鈹戦悩瀹犲缁炬儳顭烽弻銊モ攽閸℃﹩妫ら梺宕囩帛濮婂鍩€椤掆偓缁犲秹宕曢柆宥呯疇闁圭増婢樼粻娲煟濡偐甯涢柣?" );
            }

            List<ChainTunnel> chainTunnels = chainTunnelService.list(
                    new QueryWrapper<ChainTunnel>().eq("node_id", nodeId)
            );
            Set<Integer> usedPorts = chainTunnels.stream()
                    .map(ChainTunnel::getPort)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());


            List<ForwardPort> list = forwardPortService.list(new QueryWrapper<ForwardPort>().eq("node_id", nodeId).ne("forward_id", forward_id));
            Set<Integer> forwardUsedPorts = new HashSet<>();
            for (ForwardPort forwardPort : list) {
                forwardUsedPorts.add(forwardPort.getPort());
            }
            usedPorts.addAll(forwardUsedPorts);

            List<Integer> parsedPorts = TunnelServiceImpl.parsePorts(node.getPort());
            return parsedPorts.stream()
                    .filter(p -> !usedPorts.contains(p))
                    .toList();
        }
    }



    public R updateForwardGroup(Long id, String groupName) {
        UserInfo currentUser = getCurrentUserInfo();
        Forward forward = this.getById(id);
        if (forward == null) {
            return R.err("闂傚倷绀侀幖顐λ囬柆宥呯？闁圭増婢樼粈鍫熺箾閸℃ê绔惧ù婊冪秺閺屾稓浠﹂崜褋鈧帡鏌嶇紒妯烩拻闁逞屽墮缁犲秹宕曢柆宥呯疇闁圭増婢樼粻娲煟濡偐甯涢柣?" );
        }
        if (currentUser.getRoleId() != 0 && !Objects.equals(currentUser.getUserId(), forward.getUserId())) {
            return R.err("闂傚倸鍊风粈渚€骞栭锕€鐤柣妤€鐗婇崣蹇涙⒒閸喍绶遍柣鎺嶇矙閺屾盯顢曢悩鎻掑闂佹娊鏀卞Λ鍐蓟閻斿吋鐒介柨鏇楀亾妤犵偞顨婇弻鐔碱敍濞戞碍鍣ч梻?" );
        }
        forward.setGroupName(groupName);
        forward.setUpdatedTime(System.currentTimeMillis());
        this.updateById(forward);
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R updateForwardGroup(ForwardGroupUpdateDto forwardGroupUpdateDto) {
        UserInfo currentUser = getCurrentUserInfo();
        Forward forward = validateForwardExists(forwardGroupUpdateDto.getId(), currentUser);
        if (forward == null) {
            return R.err("闂傚倷绀侀幖顐λ囬柆宥呯？闁圭増婢樼粈鍫熺箾閸℃ê绔惧ù婊冪秺閺屾稓浠﹂崜褋鈧帡鏌嶇紒妯烩拻闁逞屽墮缁犲秹宕曢柆宥呯疇闁圭増婢樼粻娲煟濡偐甯涢柣?" );
        }

        String groupName = normalizeGroupName(forwardGroupUpdateDto.getGroupName());
        forward.setGroupName(groupName);
        forward.setUpdatedTime(System.currentTimeMillis());
        this.updateById(forward);
        ensureCustomGroupExists(forward.getUserId(), groupName);
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R batchUpdateForwardGroup(ForwardBatchGroupDto batchGroupDto) {
        UserInfo currentUser = getCurrentUserInfo();
        String groupName = normalizeGroupName(batchGroupDto.getGroupName());
        ensureCustomGroupExists(currentUser.getUserId(), groupName);

        for (Long id : batchGroupDto.getIds()) {
            Forward forward = validateForwardExists(id, currentUser);
            if (forward == null) {
                return R.err("闂傚倷绀侀幖顐λ囬柆宥呯？闁圭増婢樼粈鍫熺箾閸℃ê绔惧ù婊冪秺閺屾稓浠﹂崜褋鈧帡鏌嶇紒妯烩拻闁逞屽墮缁犲秹宕曢柆宥呯疇闁圭増婢樼粻娲煟濡偐甯涢柣鎾存礋閺屻劑寮幐搴㈠創婵犵鈧尙鐭欓柡灞剧洴婵℃悂鏁愰崨顓ф綆闁诲孩顔栭崳顕€宕抽敐澶婄畺闁冲搫鎳忛幆鐐淬亜閹扳晛鈧鈪查梻鍌氬€搁崐鎼佸磹閸濄儮鍋撳鐓庡籍鐎规洘绻堝浠嬵敇閻愭鍟囬梻鍌欑閻忔繈顢栭崱妯哄灁?" );
            }
            forward.setGroupName(groupName);
            forward.setUpdatedTime(System.currentTimeMillis());
            this.updateById(forward);
        }

        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R batchDeleteForward(ForwardBatchDeleteDto batchDeleteDto) {
        boolean forceDelete = Boolean.TRUE.equals(batchDeleteDto.getForceDelete());
        for (Long id : batchDeleteDto.getIds()) {
            R result = forceDelete ? forceDeleteForward(id) : deleteForward(id);
            if (result.getCode() != 0) {
                throw new RuntimeException(result.getMsg());
            }
        }
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R createForwardGroup(ForwardGroupCreateDto createDto) {
        UserInfo currentUser = getCurrentUserInfo();
        String groupName = normalizeGroupName(createDto.getGroupName());
        if (groupName.isEmpty()) {
            return R.err("闂傚倸鍊风粈渚€骞夐敍鍕殰闁圭儤鍤﹀☉妯锋婵﹩鍓欓悘濠囨倵楠炲灝鍔氭い锔诲灣閹叉挳鏁傞柨顖氫壕妤犵偛鐏濋崝姘箾婢舵稓鐣甸柟顔界懅閳ь剛鏁哥涵鍫曞几閺嶎厽鍊垫鐐茬仢閸旀岸鏌ｅΔ鈧Λ婵嗙暦閵忋倕绾ч柟瀛樻⒐椤秹姊虹憴鍕妞ゎ偄顦甸、鏃堟偐缂佹鍘?" );
        }
        ensureCustomGroupExists(currentUser.getUserId(), groupName);
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R deleteForwardGroup(Long id) {
        UserInfo currentUser = getCurrentUserInfo();
        ForwardGroup forwardGroup = forwardGroupMapper.selectById(id);
        if (forwardGroup == null) {
            return R.err("闂傚倸鍊风粈渚€骞夐敍鍕殰闁圭儤鍤﹀☉妯锋婵﹩鍓欓悘濠囨倵楠炲灝鍔氭俊顐ｇ洴閸┿垽寮崒妤€浜炬鐐茬仢閸旀岸鏌熼搹顐㈠妤犵偞顨婇幃鈺冪磼濡厧骞?" );
        }
        if (currentUser.getRoleId() != 0 && !Objects.equals(currentUser.getUserId(), forwardGroup.getUserId())) {
            return R.err("闂傚倸鍊风粈渚€骞栭锕€鐤柣妤€鐗婇崣蹇涙⒒閸喍绶遍柣鎺嶇矙閺屾盯顢曢悩鎻掑闂佹娊鏀卞Λ鍐蓟閻斿吋鐒介柨鏇楀亾妤犵偞顨婇弻鐔碱敍濞戞碍鍣ч梻?" );
        }
        forwardGroupMapper.deleteById(id);
        return R.ok();
    }

    @Override
    public R getForwardGroupList() {
        UserInfo currentUser = getCurrentUserInfo();
        List<ForwardWithTunnelDto> visibleForwards = loadVisibleForwards(currentUser);
        Map<String, Long> usedGroupCountMap = buildUsedGroupCountMap(visibleForwards);
        Map<Integer, String> userNameMap = buildVisibleUserNameMap(visibleForwards);

        List<ForwardGroupViewDto> customGroupViews = listVisibleCustomGroups(currentUser).stream()
                .map(group -> toGroupViewDto(
                        group,
                        userNameMap.get(group.getUserId()),
                        usedGroupCountMap.getOrDefault(groupKey(group.getUserId(), group.getGroupName()), 0L),
                        true))
                .sorted(Comparator.comparing(ForwardGroupViewDto::getGroupName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .collect(Collectors.toList());
        return R.ok(customGroupViews);
    }

    @Override
    public R getForwardGroups() {
        UserInfo currentUser = getCurrentUserInfo();
        List<ForwardWithTunnelDto> visibleForwards = loadVisibleForwards(currentUser);
        Map<String, Long> usedGroupCountMap = buildUsedGroupCountMap(visibleForwards);
        Map<Integer, String> userNameMap = buildVisibleUserNameMap(visibleForwards);

        List<ForwardGroup> customGroups = listVisibleCustomGroups(currentUser);
        List<ForwardGroupViewDto> customGroupViews = customGroups.stream()
                .map(group -> toGroupViewDto(group, userNameMap.get(group.getUserId()), usedGroupCountMap.getOrDefault(groupKey(group.getUserId(), group.getGroupName()), 0L), true))
                .sorted(Comparator.comparing(ForwardGroupViewDto::getGroupName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .collect(Collectors.toList());

        List<ForwardGroupViewDto> usedGroupViews = usedGroupCountMap.entrySet().stream()
                .map(entry -> {
                    Integer userId = parseUserIdFromGroupKey(entry.getKey());
                    String groupName = parseGroupNameFromGroupKey(entry.getKey());
                    return toGroupViewDto(null, userNameMap.get(userId), entry.getValue(), false, userId, groupName);
                })
                .sorted(Comparator.comparing(ForwardGroupViewDto::getGroupName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .collect(Collectors.toList());

        ForwardGroupsResponseDto response = new ForwardGroupsResponseDto();
        response.setCustomGroups(customGroupViews);
        response.setUsedGroups(usedGroupViews);
        response.setGroups(usedGroupViews.stream()
                .map(ForwardGroupViewDto::getGroupName)
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .collect(Collectors.toList()));
        return R.ok(response);
    }

    private String normalizeGroupName(String groupName) {
        return groupName == null ? "" : groupName.trim();
    }

    private void ensureCustomGroupExists(Integer userId, String groupName) {
        String normalized = normalizeGroupName(groupName);
        if (normalized.isEmpty()) {
            return;
        }

        ForwardGroup existing = forwardGroupMapper.selectOne(new QueryWrapper<ForwardGroup>()
                .eq("user_id", userId)
                .eq("group_name", normalized));
        if (existing != null) {
            return;
        }

        ForwardGroup forwardGroup = new ForwardGroup();
        forwardGroup.setUserId(userId);
        forwardGroup.setGroupName(normalized);
        forwardGroup.setStatus(1);
        long now = System.currentTimeMillis();
        forwardGroup.setCreatedTime(now);
        forwardGroup.setUpdatedTime(now);
        forwardGroupMapper.insert(forwardGroup);
    }

    private List<ForwardGroup> listVisibleCustomGroups(UserInfo currentUser) {
        QueryWrapper<ForwardGroup> queryWrapper = new QueryWrapper<>();
        if (currentUser.getRoleId() != 0) {
            queryWrapper.eq("user_id", currentUser.getUserId());
        }
        return forwardGroupMapper.selectList(queryWrapper.orderByDesc("created_time"));
    }

    private Map<String, Long> buildUsedGroupCountMap(List<ForwardWithTunnelDto> visibleForwards) {
        Map<String, Long> counts = new HashMap<>();
        if (visibleForwards == null) {
            return counts;
        }

        for (ForwardWithTunnelDto forward : visibleForwards) {
            String groupName = normalizeGroupName(forward.getGroupName());
            if (groupName.isEmpty()) {
                continue;
            }
            String key = groupKey(forward.getUserId(), groupName);
            counts.put(key, counts.getOrDefault(key, 0L) + 1L);
        }
        return counts;
    }

    private Map<Integer, String> buildVisibleUserNameMap(List<ForwardWithTunnelDto> visibleForwards) {
        Map<Integer, String> userNameMap = new HashMap<>();
        if (visibleForwards == null) {
            return userNameMap;
        }

        for (ForwardWithTunnelDto forward : visibleForwards) {
            if (forward.getUserId() != null && forward.getUserName() != null) {
                userNameMap.putIfAbsent(forward.getUserId(), forward.getUserName());
            }
        }
        return userNameMap;
    }

    private ForwardGroupViewDto toGroupViewDto(ForwardGroup group, String userName, Long forwardCount, boolean custom) {
        return toGroupViewDto(group, userName, forwardCount, custom,
                group != null ? group.getUserId() : null,
                group != null ? group.getGroupName() : null);
    }

    private ForwardGroupViewDto toGroupViewDto(ForwardGroup group, String userName, Long forwardCount, boolean custom, Integer userId, String groupName) {
        ForwardGroupViewDto dto = new ForwardGroupViewDto();
        if (group != null) {
            dto.setId(group.getId());
            dto.setCreatedTime(group.getCreatedTime());
            dto.setUpdatedTime(group.getUpdatedTime());
        }
        dto.setUserId(userId);
        dto.setUserName(userName);
        dto.setGroupName(groupName);
        dto.setForwardCount(forwardCount == null ? 0L : forwardCount);
        dto.setCustom(custom);
        return dto;
    }

    private String groupKey(Integer userId, String groupName) {
        return (userId == null ? -1 : userId) + ":" + normalizeGroupName(groupName).toLowerCase(java.util.Locale.ROOT);
    }

    private Integer parseUserIdFromGroupKey(String key) {
        if (key == null || !key.contains(":")) {
            return null;
        }
        String userPart = key.substring(0, key.indexOf(':'));
        try {
            return Integer.parseInt(userPart);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String parseGroupNameFromGroupKey(String key) {
        if (key == null || !key.contains(":")) {
            return "";
        }
        return key.substring(key.indexOf(':') + 1);
    }

    // ========== 闂傚倸鍊风粈渚€骞夐敓鐘茬闁哄洢鍨圭粻鐘虫叏濡炶浜鹃悗瑙勬礃濞茬喖鐛鈧、娆撳礂閼测斁鍋撻鍕拺缂備焦锕╁▓鏃堟煟濡も偓濡稓鍒掓繝鍥ㄦ櫆闁告挆鍜冪床?==========

    @Data
    private static class UserInfo {
        private final Integer userId;
        private final Integer roleId;
        private final String userName;

        public UserInfo(Integer userId, Integer roleId, String userName) {
            this.userId = userId;
            this.roleId = roleId;
            this.userName = userName;
        }
    }

    @Data
    private static class UserPermissionResult {
        private final boolean hasError;
        private final String errorMessage;
        private final Integer limiter;
        private final UserTunnel userTunnel;

        private UserPermissionResult(boolean hasError, String errorMessage, Integer limiter, UserTunnel userTunnel) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.limiter = limiter;
            this.userTunnel = userTunnel;
        }

        public static UserPermissionResult success(Integer limiter, UserTunnel userTunnel) {
            return new UserPermissionResult(false, null, limiter, userTunnel);
        }

        public static UserPermissionResult error(String errorMessage) {
            return new UserPermissionResult(true, errorMessage, null, null);
        }
    }

    @Data
    public static class DiagnosisResult {
        private Long nodeId;
        private String nodeName;
        private String targetIp;
        private Integer targetPort;
        private String description;
        private boolean success;
        private String message;
        private double averageTime;
        private double packetLoss;
        private long timestamp;

        // 闂傚倸鍊搁崐鐑芥嚄閸洖纾婚柟鎯х－閺嗭附鎱ㄥ璇蹭壕闂佽鍨伴惌鍌氱暦濠婂棭妲鹃柣蹇撶箳閺佸骞冭ぐ鎺戠倞妞ゅ繐瀚В銏ゆ⒑闁偛鑻晶浼存煕韫囨棑鑰挎鐐叉瀹曠喖顢橀悩鐢靛幆闂備礁澹婇悡鍫ュ窗閺嶎偆绀婃慨妞诲亾闁哄矉绲鹃幆鏃堝閳垛晛顫屾俊鐐€戦崝灞轿涘┑瀣ㄢ偓?
        private Integer fromChainType; // 1: 1, 2, 3
        private Integer fromInx;
        private Integer toChainType;
        private Integer toInx;
    }

}
