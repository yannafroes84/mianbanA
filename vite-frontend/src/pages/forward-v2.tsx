import { useEffect, useMemo, useState } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input, Textarea } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Autocomplete, AutocompleteItem } from "@heroui/autocomplete";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import toast from "react-hot-toast";
import { createForward, deleteForward, diagnoseForward, forceDeleteForward, getForwardGroups, getForwardList, pauseForwardService, resumeForwardService, updateForward, updateForwardGroup, userTunnel } from "@/api";
import { batchDeleteForwards, batchUpdateForwardGroup, createForwardGroup, deleteForwardGroupRecord, getForwardGroupList, updateForwardGroupRecord, type ForwardGroupRecord } from "@/api/forward-groups";

interface Forward {
  id: number;
  name: string;
  tunnelId: number;
  tunnelName: string;
  inIp: string;
  inPort: number;
  remoteAddr: string;
  interfaceName?: string;
  strategy: string;
  status: number;
  serviceRunning: boolean;
  userId?: number;
  userName?: string;
  groupName?: string;
  inFlow?: number;
  outFlow?: number;
}

interface Tunnel {
  id: number;
  name: string;
}

interface ForwardForm {
  id?: number;
  userId?: number;
  name: string;
  tunnelId: number | null;
  inPort: number | null;
  remoteAddr: string;
  interfaceName: string;
  strategy: string;
  groupName: string;
}

interface BatchImportRow {
  lineNo: number;
  name: string;
  tunnelId: number;
  inPort: number;
  remoteAddr: string;
  strategy: string;
  groupName: string;
}

interface GroupSection {
  key: string;
  name: string;
  forwards: Forward[];
  runningCount: number;
  totalCount: number;
  isUngrouped?: boolean;
}

const ALL_GROUP_KEY = "__all__";
const UNGROUPED_KEY = "__ungrouped__";
const normalizeGroup = (value?: string) => (value || "").trim();
const getGroupRecordName = (item: ForwardGroupRecord) => normalizeGroup(item.groupName ?? item.name);

export default function ForwardPage() {
  const [loading, setLoading] = useState(true);
  const [forwards, setForwards] = useState<Forward[]>([]);
  const [tunnels, setTunnels] = useState<Tunnel[]>([]);
  const [forwardGroups, setForwardGroups] = useState<ForwardGroupRecord[]>([]);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [portFilter, setPortFilter] = useState("");
  const [selectedGroup, setSelectedGroup] = useState("");
  const [viewMode, setViewMode] = useState<"grouped" | "direct">(() => {
    try {
      return (localStorage.getItem("forward-view-mode") as "grouped" | "direct") || "direct";
    } catch {
      return "direct";
    }
  });

  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [modalOpen, setModalOpen] = useState(false);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [groupCreateModalOpen, setGroupCreateModalOpen] = useState(false);
  const [batchMoveModalOpen, setBatchMoveModalOpen] = useState(false);
  const [batchDeleteModalOpen, setBatchDeleteModalOpen] = useState(false);
  const [batchImportModalOpen, setBatchImportModalOpen] = useState(false);
  const [groupRenameModalOpen, setGroupRenameModalOpen] = useState(false);
  const [groupDeleteModalOpen, setGroupDeleteModalOpen] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [groupCreateLoading, setGroupCreateLoading] = useState(false);
  const [batchLoading, setBatchLoading] = useState(false);
  const [batchImportLoading, setBatchImportLoading] = useState(false);
  const [groupActionLoading, setGroupActionLoading] = useState(false);
  const [diagnosingIds, setDiagnosingIds] = useState<number[]>([]);
  const [forwardToDelete, setForwardToDelete] = useState<Forward | null>(null);
  const [groupCreateValue, setGroupCreateValue] = useState("");
  const [batchTargetGroup, setBatchTargetGroup] = useState("");
  const [batchImportTunnelId, setBatchImportTunnelId] = useState<number | null>(null);
  const [batchImportGroup, setBatchImportGroup] = useState("");
  const [batchImportText, setBatchImportText] = useState("");
  const [batchImportErrors, setBatchImportErrors] = useState<string[]>([]);
  const [activeGroupRecord, setActiveGroupRecord] = useState<ForwardGroupRecord | null>(null);
  const [groupRenameValue, setGroupRenameValue] = useState("");
  const [form, setForm] = useState<ForwardForm>({
    name: "",
    tunnelId: null,
    inPort: null,
    remoteAddr: "",
    interfaceName: "",
    strategy: "fifo",
    groupName: "",
  });
  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => { loadData(); }, []);
  useEffect(() => { setSelectedIds(prev => prev.filter(id => forwards.some(item => item.id === id))); }, [forwards]);
  useEffect(() => { try { localStorage.setItem("forward-view-mode", viewMode); } catch { /* ignore */ } }, [viewMode]);

  const groupNames = useMemo(() => {
    return Array.from(new Set([
      ...forwardGroups.map(getGroupRecordName).filter(Boolean),
      ...forwards.map(item => normalizeGroup(item.groupName)).filter(Boolean),
    ])).sort((a, b) => a.localeCompare(b));
  }, [forwardGroups, forwards]);

  const visibleForwards = useMemo(() => {
    let result = [...forwards];
    if (selectedGroup === UNGROUPED_KEY) {
      result = result.filter(item => !normalizeGroup(item.groupName));
    } else if (selectedGroup) {
      result = result.filter(item => normalizeGroup(item.groupName) === selectedGroup);
    }

    if (portFilter.trim()) {
      const port = Number(portFilter.trim());
      result = Number.isFinite(port) ? result.filter(item => Number(item.inPort) === port) : [];
    }

    if (searchKeyword.trim()) {
      const keyword = searchKeyword.trim().toLowerCase();
      result = result.filter(item => [item.name, item.inIp, item.remoteAddr, item.tunnelName, item.userName, item.groupName].some(value => value?.toLowerCase().includes(keyword)));
    }
    return result;
  }, [forwards, selectedGroup, portFilter, searchKeyword]);

  const groupSections = useMemo<GroupSection[]>(() => {
    const map = new Map<string, GroupSection>();
    forwardGroups.forEach(item => {
      const name = getGroupRecordName(item);
      if (!name) return;
      map.set(name, { key: name, name, forwards: [], runningCount: 0, totalCount: 0 });
    });
    visibleForwards.forEach(item => {
      const name = normalizeGroup(item.groupName);
      const key = name || UNGROUPED_KEY;
      if (!map.has(key)) {
        map.set(key, { key, name: key === UNGROUPED_KEY ? "未分组" : key, forwards: [], runningCount: 0, totalCount: 0, isUngrouped: key === UNGROUPED_KEY });
      }
      const section = map.get(key)!;
      section.forwards.push(item);
      section.totalCount += 1;
      if (item.serviceRunning) section.runningCount += 1;
    });

    return Array.from(map.values()).filter(section => {
      if (!selectedGroup || selectedGroup === ALL_GROUP_KEY) return true;
      if (selectedGroup === UNGROUPED_KEY) return section.key === UNGROUPED_KEY;
      return section.key === selectedGroup;
    }).sort((a, b) => {
      if (a.key === UNGROUPED_KEY) return 1;
      if (b.key === UNGROUPED_KEY) return -1;
      return a.name.localeCompare(b.name);
    });
  }, [forwardGroups, visibleForwards, selectedGroup]);

  const isSelected = (id: number) => selectedIds.includes(id);
  const toggleSelected = (id: number) => setSelectedIds(prev => prev.includes(id) ? prev.filter(item => item !== id) : [...prev, id]);
  const clearSelection = () => setSelectedIds([]);
  const getCustomGroupRecord = (name: string) => forwardGroups.find(item => getGroupRecordName(item) === name && item.id);

  function formatFlow(value?: number) {
    const bytes = Number(value || 0);
    if (!bytes) return "0 B";
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(2)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  }

  function getStrategyLabel(strategy?: string) {
    if (strategy === "round") return "轮询";
    if (strategy === "random") return "随机";
    if (strategy === "hash") return "哈希";
    return "主备";
  }

  function getAddressList(value?: string) {
    return (value || "").split(",").map(item => item.trim()).filter(Boolean);
  }

  function getEntryAddresses(item: Forward) {
    const addresses = getAddressList(item.inIp);
    if (addresses.length) return addresses;
    return item.inPort ? [`:${item.inPort}`] : [];
  }

  async function copyText(value: string, label: string) {
    if (!value || value === "-") return;
    try {
      await navigator.clipboard.writeText(value);
      toast.success(`${label}已复制`);
    } catch {
      toast.error("复制失败");
    }
  }

  async function loadData(preserveLoading = true) {
    setLoading(preserveLoading);
    try {
      const [forwardsRes, tunnelsRes, groupRes, legacyGroupsRes] = await Promise.allSettled([
        getForwardList(),
        userTunnel(),
        getForwardGroupList(),
        getForwardGroups(),
      ]);

      const forwardsResponse = forwardsRes.status === "fulfilled" ? forwardsRes.value : null;
      const tunnelsResponse = tunnelsRes.status === "fulfilled" ? tunnelsRes.value : null;
      const groupsResponse = groupRes.status === "fulfilled" ? groupRes.value : null;
      const legacyGroupsResponse = legacyGroupsRes.status === "fulfilled" ? legacyGroupsRes.value : null;

      if (forwardsResponse?.code === 0) {
        const data = (forwardsResponse.data || []).map((item: any) => ({ ...item, serviceRunning: item.status === 1 })) as Forward[];
        setForwards(data);

        const legacyGroupNames = legacyGroupsResponse?.code === 0
          ? (Array.isArray(legacyGroupsResponse.data) ? legacyGroupsResponse.data : legacyGroupsResponse.data?.groups)
          : [];
        const nextGroups: ForwardGroupRecord[] = groupsResponse?.code === 0 && Array.isArray(groupsResponse.data)
          ? groupsResponse.data
              .map((item: ForwardGroupRecord) => {
                const name = getGroupRecordName(item);
                return { ...item, name, groupName: name };
              })
              .filter((item: ForwardGroupRecord) => getGroupRecordName(item))
          : Array.isArray(legacyGroupNames)
            ? legacyGroupNames.map((name: string) => ({ name: normalizeGroup(name), groupName: normalizeGroup(name) })).filter((item: ForwardGroupRecord) => getGroupRecordName(item))
            : [];
        setForwardGroups(nextGroups);
      } else {
        toast.error(forwardsResponse?.msg || "获取转发列表失败");
      }

      if (tunnelsResponse?.code === 0) {
        setTunnels(tunnelsResponse.data || []);
      }
    } catch (error) {
      console.error(error);
      toast.error("加载数据失败");
    } finally {
      setLoading(false);
    }
  }

  function validateForm() {
    const next: Record<string, string> = {};
    if (!form.name.trim()) next.name = "请输入转发名称";
    if (!form.tunnelId) next.tunnelId = "请选择隧道";
    if (!form.remoteAddr.trim()) next.remoteAddr = "请输入目标地址";
    if (!form.inPort || Number.isNaN(Number(form.inPort)) || Number(form.inPort) < 1 || Number(form.inPort) > 65535) next.inPort = "端口必须在 1-65535 之间";
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  function openCreateModal(groupName = "") {
    setForm({ name: "", tunnelId: null, inPort: null, remoteAddr: "", interfaceName: "", strategy: "fifo", groupName: normalizeGroup(groupName) || (selectedGroup && selectedGroup !== UNGROUPED_KEY ? selectedGroup : "") });
    setErrors({});
    setModalOpen(true);
  }

  function openBatchImport(groupName = "") {
    const defaultGroup = normalizeGroup(groupName) || (selectedGroup && selectedGroup !== UNGROUPED_KEY ? selectedGroup : "");
    const currentTunnelStillExists = batchImportTunnelId && tunnels.some(tunnel => tunnel.id === batchImportTunnelId);
    setBatchImportTunnelId(currentTunnelStillExists ? batchImportTunnelId : (tunnels.length === 1 ? tunnels[0].id : null));
    setBatchImportGroup(defaultGroup);
    setBatchImportErrors([]);
    setBatchImportModalOpen(true);
  }

  function openEditModal(item: Forward) {
    setForm({
      id: item.id,
      userId: item.userId,
      name: item.name,
      tunnelId: item.tunnelId,
      inPort: item.inPort,
      remoteAddr: item.remoteAddr.split(",").join("\n"),
      interfaceName: item.interfaceName || "",
      strategy: item.strategy || "fifo",
      groupName: item.groupName || "",
    });
    setErrors({});
    setModalOpen(true);
  }

  async function saveForward() {
    if (!validateForm()) return;
    setSubmitLoading(true);
    try {
      const remoteAddr = form.remoteAddr.split("\n").map(item => item.trim()).filter(Boolean).join(",");
      const payload = { name: form.name, tunnelId: form.tunnelId, inPort: form.inPort, remoteAddr, interfaceName: form.interfaceName, strategy: form.strategy, groupName: normalizeGroup(form.groupName) };
      if (payload.groupName) {
        try { await createForwardGroup({ name: payload.groupName }); } catch { /* ignore */ }
        setForwardGroups(prev => prev.some(item => getGroupRecordName(item) === payload.groupName) ? prev : [...prev, { name: payload.groupName, groupName: payload.groupName }]);
      }
      const res = form.id ? await updateForward({ ...payload, id: form.id, userId: form.userId }) : await createForward(payload);
      if (res.code === 0) {
        toast.success(form.id ? "修改成功" : "创建成功");
        setModalOpen(false);
        loadData(false);
      } else {
        toast.error(res.msg || "操作失败");
      }
    } catch {
      toast.error("操作失败");
    } finally {
      setSubmitLoading(false);
    }
  }

  async function toggleService(item: Forward) {
    const next = !item.serviceRunning;
    setForwards(prev => prev.map(forward => forward.id === item.id ? { ...forward, serviceRunning: next } : forward));
    try {
      const res = next ? await resumeForwardService(item.id) : await pauseForwardService(item.id);
      if (res.code !== 0) throw new Error(res.msg || "toggle failed");
      setForwards(prev => prev.map(forward => forward.id === item.id ? { ...forward, serviceRunning: next, status: next ? 1 : 0 } : forward));
    } catch {
      setForwards(prev => prev.map(forward => forward.id === item.id ? { ...forward, serviceRunning: !next } : forward));
      toast.error("服务切换失败");
    }
  }

  function handleDelete(item: Forward) {
    setForwardToDelete(item);
    setDeleteModalOpen(true);
  }

  async function confirmDelete() {
    if (!forwardToDelete) return;
    setDeleteLoading(true);
    try {
      const res = await deleteForward(forwardToDelete.id);
      if (res.code === 0) {
        toast.success("删除成功");
        setDeleteModalOpen(false);
        setForwardToDelete(null);
        loadData(false);
        return;
      }
      if (window.confirm(`常规删除失败：${res.msg || "删除失败"}\n\n是否强制删除？`)) {
        const forceRes = await forceDeleteForward(forwardToDelete.id);
        if (forceRes.code === 0) {
          toast.success("强制删除成功");
          setDeleteModalOpen(false);
          setForwardToDelete(null);
          loadData(false);
        } else {
          toast.error(forceRes.msg || "强制删除失败");
        }
      }
    } catch {
      toast.error("删除失败");
    } finally {
      setDeleteLoading(false);
    }
  }

  async function createGroup() {
    const name = normalizeGroup(groupCreateValue);
    if (!name) return toast.error("请输入分组名称");
    setGroupCreateLoading(true);
    try {
      try { await createForwardGroup({ name }); } catch { /* backend may not be ready */ }
      setForwardGroups(prev => prev.some(item => getGroupRecordName(item) === name) ? prev : [...prev, { name, groupName: name }]);
      toast.success("分组已创建");
      setGroupCreateModalOpen(false);
      setGroupCreateValue("");
      loadData(false);
    } finally {
      setGroupCreateLoading(false);
    }
  }

  function openRenameGroup(groupName: string) {
    const record = getCustomGroupRecord(groupName);
    if (!record?.id) return toast.error("只能修改自定义分组");
    setActiveGroupRecord(record);
    setGroupRenameValue(getGroupRecordName(record));
    setGroupRenameModalOpen(true);
  }

  async function renameGroup() {
    const record = activeGroupRecord;
    const oldName = record ? getGroupRecordName(record) : "";
    const newName = normalizeGroup(groupRenameValue);
    if (!record?.id) return toast.error("分组不存在");
    if (!newName) return toast.error("请输入分组名称");
    setGroupActionLoading(true);
    try {
      const res = await updateForwardGroupRecord({ id: record.id, groupName: newName });
      if (res.code !== 0) throw new Error(res.msg || "rename failed");
      if (selectedGroup === oldName) setSelectedGroup(newName);
      toast.success("分组已改名");
      setGroupRenameModalOpen(false);
      setActiveGroupRecord(null);
      setGroupRenameValue("");
      loadData(false);
    } catch (error: any) {
      toast.error(error?.message || "分组改名失败");
    } finally {
      setGroupActionLoading(false);
    }
  }

  function openDeleteGroup(groupName: string) {
    const record = getCustomGroupRecord(groupName);
    if (!record?.id) return toast.error("只能删除自定义分组");
    setActiveGroupRecord(record);
    setGroupDeleteModalOpen(true);
  }

  async function deleteGroup() {
    const record = activeGroupRecord;
    const groupName = record ? getGroupRecordName(record) : "";
    if (!record?.id) return toast.error("分组不存在");
    setGroupActionLoading(true);
    try {
      const res = await deleteForwardGroupRecord(record.id);
      if (res.code !== 0) throw new Error(res.msg || "delete failed");
      if (selectedGroup === groupName) setSelectedGroup("");
      toast.success("分组已删除，规则已移至未分组");
      setGroupDeleteModalOpen(false);
      setActiveGroupRecord(null);
      loadData(false);
    } catch (error: any) {
      toast.error(error?.message || "删除分组失败");
    } finally {
      setGroupActionLoading(false);
    }
  }

  async function batchMoveGroup() {
    const ids = selectedIds.filter(id => id > 0);
    const groupName = normalizeGroup(batchTargetGroup);
    if (!ids.length) return toast.error("请先选择转发");
    setBatchLoading(true);
    try {
      if (groupName) {
        try { await createForwardGroup({ name: groupName }); } catch { /* ignore */ }
        setForwardGroups(prev => prev.some(item => getGroupRecordName(item) === groupName) ? prev : [...prev, { name: groupName, groupName }]);
      }
      const res = await batchUpdateForwardGroup({ ids, groupName });
      if (res.code !== 0) throw new Error(res.msg || "batch update failed");
      toast.success(groupName ? "已批量切换分组" : "已移至未分组");
      setSelectedIds([]);
      setBatchMoveModalOpen(false);
      setBatchTargetGroup("");
      loadData(false);
    } catch {
      try {
        await Promise.all(ids.map(id => updateForwardGroup(id, groupName)));
        toast.success(groupName ? "已批量切换分组" : "已移至未分组");
        setSelectedIds([]);
        setBatchMoveModalOpen(false);
        setBatchTargetGroup("");
        loadData(false);
      } catch {
        toast.error("批量切换分组失败");
      }
    } finally {
      setBatchLoading(false);
    }
  }

  async function batchDelete() {
    const ids = selectedIds.filter(id => id > 0);
    if (!ids.length) return toast.error("请先选择转发");
    setBatchLoading(true);
    try {
      const res = await batchDeleteForwards(ids);
      if (res.code !== 0) throw new Error(res.msg || "batch delete failed");
      toast.success("批量删除成功");
      setSelectedIds([]);
      setBatchDeleteModalOpen(false);
      loadData(false);
    } catch {
      try {
        await Promise.all(ids.map(id => deleteForward(id)));
        toast.success("批量删除成功");
        setSelectedIds([]);
        setBatchDeleteModalOpen(false);
        loadData(false);
      } catch {
        toast.error("批量删除失败");
      }
    } finally {
      setBatchLoading(false);
    }
  }

  async function runDiagnosis(item: Forward) {
    setDiagnosingIds(prev => prev.includes(item.id) ? prev : [...prev, item.id]);
    try {
      const res = await diagnoseForward(item.id);
      if (res.code !== 0) throw new Error(res.msg || "诊断失败");
      const results = Array.isArray(res.data?.results) ? res.data.results : [];
      const failedCount = results.filter((result: any) => result && result.success === false).length;
      if (failedCount > 0) {
        toast.error(`诊断完成，发现 ${failedCount} 项异常`);
      } else {
        toast.success("诊断正常");
      }
    } catch (error: any) {
      toast.error(error?.message || "诊断失败");
    } finally {
      setDiagnosingIds(prev => prev.filter(id => id !== item.id));
    }
  }

  function splitImportLine(line: string) {
    const trimmed = line.trim();
    if (trimmed.includes(",")) {
      const result: string[] = [];
      let current = "";
      let quote = "";
      for (let i = 0; i < trimmed.length; i += 1) {
        const char = trimmed[i];
        if ((char === "\"" || char === "'") && (!quote || quote === char)) {
          if (quote === char && trimmed[i + 1] === char) {
            current += char;
            i += 1;
          } else {
            quote = quote ? "" : char;
          }
          continue;
        }
        if (char === "," && !quote) {
          result.push(current.trim());
          current = "";
          continue;
        }
        current += char;
      }
      result.push(current.trim());
      return result;
    }
    return trimmed.split(trimmed.includes("\t") ? /\t+/ : /\s+/).map(item => item.trim()).filter(Boolean);
  }

  function isPortText(value?: string) {
    if (!value || !/^\d+$/.test(value.trim())) return false;
    const port = Number(value);
    return Number.isInteger(port) && port >= 1 && port <= 65535;
  }

  function isImportHeader(line: string) {
    const text = line.replace(/\s/g, "").toLowerCase();
    return (text.includes("name") || text.includes("名称")) && (text.includes("port") || text.includes("端口"));
  }

  function parseBatchImportRows() {
    const errors: string[] = [];
    const rows: BatchImportRow[] = [];
    const tunnelId = batchImportTunnelId;
    const defaultGroup = normalizeGroup(batchImportGroup);

    if (!tunnelId) {
      errors.push("请选择关联隧道");
    }

    batchImportText.split(/\r?\n/).forEach((rawLine, index) => {
      const lineNo = index + 1;
      const line = rawLine.trim();
      if (!line || line.startsWith("#") || isImportHeader(line)) return;

      const parts = splitImportLine(line);
      if (parts.length < 2) {
        errors.push(`第 ${lineNo} 行格式错误：至少需要入口端口和目标地址`);
        return;
      }

      const firstIsPort = isPortText(parts[0]);
      const name = firstIsPort ? (parts[2] || `转发-${parts[0]}`) : parts[0];
      const inPortText = firstIsPort ? parts[0] : parts[1];
      const remoteText = firstIsPort ? parts[1] : parts[2];
      const groupText = normalizeGroup(parts[3]) || defaultGroup;

      if (!name?.trim()) {
        errors.push(`第 ${lineNo} 行缺少转发名称`);
        return;
      }
      if (!isPortText(inPortText)) {
        errors.push(`第 ${lineNo} 行入口端口无效：${inPortText || "空"}`);
        return;
      }
      if (!remoteText?.trim()) {
        errors.push(`第 ${lineNo} 行缺少目标地址`);
        return;
      }

      rows.push({
        lineNo,
        name: name.trim(),
        tunnelId: tunnelId || 0,
        inPort: Number(inPortText),
        remoteAddr: remoteText.split(";").map(item => item.trim()).filter(Boolean).join(","),
        strategy: "fifo",
        groupName: groupText,
      });
    });

    if (!rows.length && !errors.length) {
      errors.push("请至少输入一条转发规则");
    }

    return { rows: errors.length ? [] : rows, errors };
  }

  async function batchImportForwards() {
    const { rows, errors } = parseBatchImportRows();
    if (errors.length) {
      setBatchImportErrors(errors);
      toast.error(errors[0]);
      return;
    }

    setBatchImportLoading(true);
    setBatchImportErrors([]);
    const failed: string[] = [];
    let successCount = 0;

    try {
      const groups = Array.from(new Set(rows.map(row => row.groupName).filter(Boolean)));
      for (const groupName of groups) {
        try { await createForwardGroup({ name: groupName }); } catch { /* ignore */ }
      }
      if (groups.length) {
        setForwardGroups(prev => {
          const exists = new Set(prev.map(getGroupRecordName));
          const additions = groups.filter(name => !exists.has(name)).map(name => ({ name, groupName: name }));
          return additions.length ? [...prev, ...additions] : prev;
        });
      }

      for (const row of rows) {
        const res = await createForward({
          name: row.name,
          tunnelId: row.tunnelId,
          inPort: row.inPort,
          remoteAddr: row.remoteAddr,
          strategy: row.strategy,
          groupName: row.groupName,
        });
        if (res.code === 0) {
          successCount += 1;
        } else {
          failed.push(`第 ${row.lineNo} 行：${res.msg || "创建失败"}`);
        }
      }

      if (successCount > 0) {
        await loadData(false);
      }

      if (failed.length) {
        setBatchImportErrors(failed);
        toast.error(`导入完成：成功 ${successCount} 条，失败 ${failed.length} 条`);
        return;
      }

      toast.success(`批量导入成功：${successCount} 条`);
      setBatchImportModalOpen(false);
      setBatchImportText("");
      setBatchImportGroup("");
      setBatchImportErrors([]);
    } catch {
      toast.error("批量导入失败");
    } finally {
      setBatchImportLoading(false);
    }
  }

  function groupSelectItems() {
    return [
      { key: ALL_GROUP_KEY, label: "全部分组" },
      { key: UNGROUPED_KEY, label: "未分组" },
      ...groupNames.map(name => ({ key: name, label: name })),
    ];
  }

  function renderCard(item: Forward) {
    const entryAddresses = getEntryAddresses(item);
    const entryMain = entryAddresses[0] || "-";
    const entryExtra = entryAddresses.length > 1 ? ` (+${entryAddresses.length - 1}个)` : "";
    const targetMain = getAddressList(item.remoteAddr)[0] || item.remoteAddr || "-";
    const isDiagnosing = diagnosingIds.includes(item.id);

    return (
      <Card key={item.id} className={`shadow-sm border rounded-xl transition-shadow duration-200 ${isSelected(item.id) ? "border-primary ring-2 ring-primary/20" : "border-divider hover:shadow-md"}`}>
        <CardBody className="p-3">
          <div className="space-y-3">
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-start gap-2 min-w-0">
                <input type="checkbox" className="mt-1 h-4 w-4 shrink-0" checked={isSelected(item.id)} onChange={() => toggleSelected(item.id)} />
                <div className="min-w-0">
                  <div className="flex items-center gap-2 min-w-0">
                    <h3 className="font-semibold text-foreground truncate text-sm">{item.name}</h3>
                    {item.serviceRunning && <Chip size="sm" color="success" variant="flat">正常</Chip>}
                  </div>
                  <p className="text-xs text-default-500 truncate">{item.groupName || "未分组"}</p>
                </div>
              </div>
              <Switch size="sm" isSelected={item.serviceRunning} onValueChange={() => toggleService(item)} isDisabled={item.status !== 0 && item.status !== 1} />
            </div>

            <div className="space-y-1.5">
              <div className="flex items-center justify-between gap-2 rounded-md border border-divider bg-default-50 px-2 py-1.5 text-xs">
                <span className="min-w-0 truncate">
                  <span className="text-default-500">入口：</span>
                  <span className="font-mono text-foreground">{entryMain}</span>
                  {entryExtra && <span className="ml-1 text-default-500">{entryExtra}</span>}
                </span>
                <Button size="sm" variant="light" className="h-5 min-w-0 px-1 text-default-400" onPress={() => copyText(entryAddresses.join("\n"), "入口")}>复制</Button>
              </div>
              <div className="rounded-md border border-divider bg-default-50 px-2 py-1.5 text-xs truncate">
                <span className="text-default-500">目标：</span>
                <span className="font-mono text-foreground">{targetMain}</span>
                {getAddressList(item.remoteAddr).length > 1 && <span className="ml-1 text-default-500">(+{getAddressList(item.remoteAddr).length - 1}个)</span>}
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <Chip size="sm" color="primary" variant="flat">{getStrategyLabel(item.strategy)}</Chip>
              <Chip size="sm" color="secondary" variant="flat">上行 {formatFlow(item.inFlow)}</Chip>
              <Chip size="sm" color="success" variant="flat">下行 {formatFlow(item.outFlow)}</Chip>
            </div>

            <div className="grid grid-cols-3 gap-2 pt-1">
              <Button size="sm" variant="flat" color="primary" className="min-w-0" onPress={() => openEditModal(item)}>编辑</Button>
              <Button size="sm" variant="flat" color="warning" className="min-w-0" onPress={() => runDiagnosis(item)} isLoading={isDiagnosing}>诊断</Button>
              <Button size="sm" variant="flat" color="danger" className="flex-1" onPress={() => handleDelete(item)}>删除</Button>
            </div>
          </div>
        </CardBody>
      </Card>
    );
  }

  function renderMainBody() {
    if (viewMode === "grouped") {
      return groupSections.length > 0 ? (
        <div className="space-y-6">
          {groupSections.map(section => {
            const groupRecord = getCustomGroupRecord(section.name);
            return (
              <Card key={section.key} className="shadow-sm border border-divider overflow-hidden">
                <CardHeader className="pb-3">
                  <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-3 w-full">
                    <div className="min-w-0">
                      <h2 className="text-base font-semibold truncate">{section.name}</h2>
                      <p className="text-xs text-default-500">{section.totalCount} 个转发，{section.runningCount} 个运行中</p>
                    </div>
                    {!section.isUngrouped && (
                      <div className="flex flex-wrap items-center gap-2">
                        {groupRecord?.id && <Button size="sm" variant="flat" color="default" onPress={() => openRenameGroup(section.name)}>改名</Button>}
                        {groupRecord?.id && <Button size="sm" variant="flat" color="danger" onPress={() => openDeleteGroup(section.name)}>删除分组</Button>}
                        <Button size="sm" variant="flat" color="secondary" onPress={() => openBatchImport(section.name)}>批量导入</Button>
                        <Button size="sm" variant="flat" color="primary" onPress={() => openCreateModal(section.name)}>+ 新增到此分组</Button>
                      </div>
                    )}
                  </div>
                </CardHeader>
                <CardBody className="pt-0">
                  {section.forwards.length > 0 ? (
                    <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4 gap-4">
                      {section.forwards.map(renderCard)}
                    </div>
                  ) : (
                    <div className="rounded-xl border border-dashed border-divider p-6 text-center">
                      <p className="text-sm text-default-500 mb-3">当前分组还没有转发</p>
                      {!section.isUngrouped && (
                        <div className="flex flex-wrap items-center justify-center gap-2">
                          <Button size="sm" color="secondary" variant="flat" onPress={() => openBatchImport(section.name)}>批量导入到此分组</Button>
                          <Button size="sm" color="primary" variant="flat" onPress={() => openCreateModal(section.name)}>在此分组新增规则</Button>
                        </div>
                      )}
                    </div>
                  )}
                </CardBody>
              </Card>
            );
          })}
        </div>
      ) : (
        <Card className="shadow-sm border border-divider">
          <CardBody className="text-center py-16">
            <Spinner size="sm" />
            <p className="text-default-500 mt-3">暂无转发配置</p>
          </CardBody>
        </Card>
      );
    }

    if (!visibleForwards.length) {
      return (
        <Card className="shadow-sm border border-divider">
          <CardBody className="text-center py-16">
            <Spinner size="sm" />
            <p className="text-default-500 mt-3">暂无转发配置</p>
          </CardBody>
        </Card>
      );
    }

    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4 gap-4">
        {visibleForwards.map(renderCard)}
      </div>
    );
  }

  if (loading) {
    return <div className="flex items-center justify-center h-64"><Spinner size="sm" /></div>;
  }

  return (
    <div className="px-3 lg:px-6 py-8">
      <div className="flex flex-col gap-3 mb-4">
        <div className="grid grid-cols-1 xl:grid-cols-12 gap-3">
          <Input isClearable size="sm" placeholder="搜索名称、地址、隧道、分组..." value={searchKeyword} onValueChange={setSearchKeyword} className="xl:col-span-4" />
          <Input isClearable size="sm" type="number" placeholder="端口精确搜索" value={portFilter} onValueChange={setPortFilter} className="xl:col-span-2" />
          <Select size="sm" placeholder="全部分组" selectedKeys={selectedGroup ? new Set([selectedGroup]) : new Set([ALL_GROUP_KEY])} onSelectionChange={(keys) => { const selected = Array.from(keys)[0] as string; setSelectedGroup(selected === ALL_GROUP_KEY ? "" : (selected || "")); }} className="xl:col-span-3" items={groupSelectItems()}>
            {(item: { key: string; label: string }) => <SelectItem key={item.key}>{item.label}</SelectItem>}
          </Select>
          <div className="flex items-center justify-end gap-2 xl:col-span-3">
            <Button size="sm" variant="flat" color="primary" onPress={() => setGroupCreateModalOpen(true)}>新建分组</Button>
            <Button size="sm" variant="flat" color="default" onPress={() => setViewMode(prev => prev === "grouped" ? "direct" : "grouped")}>{viewMode === "grouped" ? "切到直列" : "切到分组"}</Button>
            <Button size="sm" variant="flat" color="secondary" onPress={() => openBatchImport()}>批量导入</Button>
            <Button size="sm" variant="flat" color="primary" onPress={() => openCreateModal()}>新增</Button>
          </div>
        </div>

        {selectedIds.length > 0 && (
          <div className="flex flex-wrap items-center gap-2 rounded-xl border border-primary/20 bg-primary/5 px-3 py-2">
            <Chip color="primary" variant="flat" size="sm">已选择 {selectedIds.length} 项</Chip>
            <Button size="sm" color="primary" variant="flat" onPress={() => setBatchMoveModalOpen(true)}>批量切换分组</Button>
            <Button size="sm" color="danger" variant="flat" onPress={() => setBatchDeleteModalOpen(true)}>批量删除</Button>
            <Button size="sm" variant="light" onPress={clearSelection}>清空选择</Button>
          </div>
        )}
      </div>

      {renderMainBody()}

      <Modal isOpen={modalOpen} onOpenChange={setModalOpen} size="2xl" scrollBehavior="outside" backdrop="blur" placement="center">
        <ModalContent>
          {(onClose) => (
            <>
              <ModalHeader>{form.id ? "编辑转发" : "新增转发"}</ModalHeader>
              <ModalBody>
                <div className="space-y-4">
                  <Input label="转发名称" value={form.name} onValueChange={(value) => setForm(prev => ({ ...prev, name: value }))} isInvalid={!!errors.name} errorMessage={errors.name} />
                  <Select label="关联隧道" selectedKeys={form.tunnelId ? new Set([String(form.tunnelId)]) : new Set()} onSelectionChange={(keys) => { const selected = Array.from(keys)[0] as string; setForm(prev => ({ ...prev, tunnelId: selected ? Number(selected) : null })); }} isInvalid={!!errors.tunnelId} errorMessage={errors.tunnelId}>
                    {tunnels.map(tunnel => <SelectItem key={String(tunnel.id)}>{tunnel.name}</SelectItem>)}
                  </Select>
                  <Input label="入口端口" type="number" value={form.inPort ? String(form.inPort) : ""} onValueChange={(value) => setForm(prev => ({ ...prev, inPort: value ? Number(value) : null }))} isInvalid={!!errors.inPort} errorMessage={errors.inPort} />
                  <Textarea label="目标地址" value={form.remoteAddr} onChange={(event) => setForm(prev => ({ ...prev, remoteAddr: event.target.value }))} isInvalid={!!errors.remoteAddr} errorMessage={errors.remoteAddr} />
                  <Autocomplete label="分组" placeholder="输入或选择分组" allowsCustomValue selectedKey={form.groupName || ""} onSelectionChange={(key) => setForm(prev => ({ ...prev, groupName: String(key || "") }))} onInputChange={(value) => setForm(prev => ({ ...prev, groupName: value }))} defaultItems={groupNames.map(name => ({ key: name, label: name }))}>
                    {(item: { key: string; label: string }) => <AutocompleteItem key={item.key}>{item.label}</AutocompleteItem>}
                  </Autocomplete>
                </div>
              </ModalBody>
              <ModalFooter>
                <Button variant="light" onPress={onClose}>取消</Button>
                <Button color="primary" onPress={saveForward} isLoading={submitLoading}>保存</Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>

      <Modal isOpen={deleteModalOpen} onOpenChange={setDeleteModalOpen} size="md">
        <ModalContent>
          {(onClose) => (
            <>
              <ModalHeader>确认删除</ModalHeader>
              <ModalBody><p>确定要删除转发 <b>{forwardToDelete?.name}</b> 吗？</p></ModalBody>
              <ModalFooter>
                <Button variant="light" onPress={onClose}>取消</Button>
                <Button color="danger" onPress={confirmDelete} isLoading={deleteLoading}>删除</Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>

      <Modal isOpen={groupCreateModalOpen} onOpenChange={setGroupCreateModalOpen} size="md">
        <ModalContent>
          {(onClose) => (
            <>
              <ModalHeader>新建分组</ModalHeader>
              <ModalBody><Input label="分组名称" value={groupCreateValue} onValueChange={setGroupCreateValue} /></ModalBody>
              <ModalFooter>
                <Button variant="light" onPress={onClose}>取消</Button>
                <Button color="primary" onPress={createGroup} isLoading={groupCreateLoading}>创建</Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>

      <Modal isOpen={groupRenameModalOpen} onOpenChange={setGroupRenameModalOpen} size="md">
        <ModalContent>
          {(onClose) => (
            <>
              <ModalHeader>分组改名</ModalHeader>
              <ModalBody>
                <Input label="新分组名称" value={groupRenameValue} onValueChange={setGroupRenameValue} />
                <p className="text-xs text-default-500">改名后，该分组内现有转发规则会同步切换到新分组名。</p>
              </ModalBody>
              <ModalFooter>
                <Button variant="light" onPress={onClose}>取消</Button>
                <Button color="primary" onPress={renameGroup} isLoading={groupActionLoading}>保存</Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>

      <Modal isOpen={groupDeleteModalOpen} onOpenChange={setGroupDeleteModalOpen} size="md">
        <ModalContent>
          {(onClose) => (
            <>
              <ModalHeader>删除分组</ModalHeader>
              <ModalBody>
                <p>确定删除分组 <b>{activeGroupRecord ? getGroupRecordName(activeGroupRecord) : ""}</b> 吗？</p>
                <p className="text-xs text-default-500">分组内的转发规则不会删除，会自动移至未分组。</p>
              </ModalBody>
              <ModalFooter>
                <Button variant="light" onPress={onClose}>取消</Button>
                <Button color="danger" onPress={deleteGroup} isLoading={groupActionLoading}>删除分组</Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>

      <Modal isOpen={batchMoveModalOpen} onOpenChange={setBatchMoveModalOpen} size="md">
        <ModalContent>
          {(onClose) => (
            <>
              <ModalHeader>批量切换分组</ModalHeader>
              <ModalBody>
                <Autocomplete label="目标分组" placeholder="输入或选择目标分组" allowsCustomValue selectedKey={batchTargetGroup} onSelectionChange={(key) => setBatchTargetGroup(String(key || ""))} onInputChange={setBatchTargetGroup} defaultItems={groupNames.map(name => ({ key: name, label: name }))}>
                  {(item: { key: string; label: string }) => <AutocompleteItem key={item.key}>{item.label}</AutocompleteItem>}
                </Autocomplete>
                <p className="text-xs text-default-500 mt-2">留空表示移至未分组</p>
              </ModalBody>
              <ModalFooter>
                <Button variant="light" onPress={onClose}>取消</Button>
                <Button color="primary" onPress={batchMoveGroup} isLoading={batchLoading}>确定</Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>

      <Modal isOpen={batchDeleteModalOpen} onOpenChange={setBatchDeleteModalOpen} size="md">
        <ModalContent>
          {(onClose) => (
            <>
              <ModalHeader>批量删除</ModalHeader>
              <ModalBody><p>确定删除已选择的 {selectedIds.length} 条转发吗？</p></ModalBody>
              <ModalFooter>
                <Button variant="light" onPress={onClose}>取消</Button>
                <Button color="danger" onPress={batchDelete} isLoading={batchLoading}>删除</Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>

      <Modal isOpen={batchImportModalOpen} onOpenChange={setBatchImportModalOpen} size="3xl" scrollBehavior="outside" backdrop="blur" placement="center">
        <ModalContent>
          {(onClose) => (
            <>
              <ModalHeader>批量导入转发规则</ModalHeader>
              <ModalBody>
                <div className="space-y-4">
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                    <Select label="关联隧道" selectedKeys={batchImportTunnelId ? new Set([String(batchImportTunnelId)]) : new Set()} onSelectionChange={(keys) => { const selected = Array.from(keys)[0] as string; setBatchImportTunnelId(selected ? Number(selected) : null); }}>
                      {tunnels.map(tunnel => <SelectItem key={String(tunnel.id)}>{tunnel.name}</SelectItem>)}
                    </Select>
                    <Autocomplete label="导入分组" placeholder="留空表示未分组" allowsCustomValue selectedKey={batchImportGroup} onSelectionChange={(key) => setBatchImportGroup(String(key || ""))} onInputChange={setBatchImportGroup} defaultItems={groupNames.map(name => ({ key: name, label: name }))}>
                      {(item: { key: string; label: string }) => <AutocompleteItem key={item.key}>{item.label}</AutocompleteItem>}
                    </Autocomplete>
                  </div>

                  <Textarea
                    label="导入内容"
                    minRows={10}
                    placeholder={"每行一条，支持两种格式：\n名称,入口端口,目标地址,分组\n入口端口,目标地址,名称,分组\n\n示例：\n香港-01,22101,1.1.1.1:80,香港\n22102,2.2.2.2:443,香港-02,香港\n# 多个目标可用分号：香港-03,22103,1.1.1.1:80;2.2.2.2:80,香港"}
                    value={batchImportText}
                    onChange={(event) => setBatchImportText(event.target.value)}
                  />

                  <div className="rounded-xl bg-default-50 px-3 py-2 text-xs text-default-600 space-y-1">
                    <p>说明：分组列可省略，省略时使用上方“导入分组”；目标地址里的多个目标用英文分号分隔。</p>
                    <p>导入会逐条创建，某一条失败不会阻断后续规则，失败原因会显示在下方。</p>
                  </div>

                  {batchImportErrors.length > 0 && (
                    <div className="rounded-xl border border-danger/30 bg-danger/5 px-3 py-2">
                      <p className="text-sm font-medium text-danger mb-2">导入错误</p>
                      <div className="max-h-40 overflow-auto space-y-1 text-xs text-danger">
                        {batchImportErrors.slice(0, 30).map((error, index) => <p key={`${error}-${index}`}>{error}</p>)}
                        {batchImportErrors.length > 30 && <p>还有 {batchImportErrors.length - 30} 条错误未显示</p>}
                      </div>
                    </div>
                  )}
                </div>
              </ModalBody>
              <ModalFooter>
                <Button variant="light" onPress={onClose}>取消</Button>
                <Button color="primary" onPress={batchImportForwards} isLoading={batchImportLoading}>开始导入</Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>
    </div>
  );
}
