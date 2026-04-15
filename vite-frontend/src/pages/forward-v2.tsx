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
import { createForward, deleteForward, forceDeleteForward, getForwardGroups, getForwardList, pauseForwardService, resumeForwardService, updateForward, updateForwardGroup, userTunnel } from "@/api";
import { batchDeleteForwards, batchUpdateForwardGroup, createForwardGroup, getForwardGroupList, type ForwardGroupRecord } from "@/api/forward-groups";

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
  const [submitLoading, setSubmitLoading] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [groupCreateLoading, setGroupCreateLoading] = useState(false);
  const [batchLoading, setBatchLoading] = useState(false);
  const [forwardToDelete, setForwardToDelete] = useState<Forward | null>(null);
  const [groupCreateValue, setGroupCreateValue] = useState("");
  const [batchTargetGroup, setBatchTargetGroup] = useState("");
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

  function groupSelectItems() {
    return [
      { key: ALL_GROUP_KEY, label: "全部分组" },
      { key: UNGROUPED_KEY, label: "未分组" },
      ...groupNames.map(name => ({ key: name, label: name })),
    ];
  }

  function renderCard(item: Forward) {
    return (
      <Card key={item.id} className={`shadow-sm border transition-shadow duration-200 ${isSelected(item.id) ? "border-primary ring-2 ring-primary/20" : "border-divider hover:shadow-md"}`}>
        <CardHeader className="pb-2">
          <div className="flex items-start justify-between gap-3 w-full">
            <div className="flex items-start gap-2 flex-1 min-w-0">
              <input type="checkbox" className="mt-1 h-4 w-4" checked={isSelected(item.id)} onChange={() => toggleSelected(item.id)} />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2 min-w-0">
                  <h3 className="font-semibold text-foreground truncate text-sm">{item.name}</h3>
                  {item.groupName && <Chip size="sm" variant="flat" color="secondary">{item.groupName}</Chip>}
                </div>
                <p className="text-xs text-default-500 truncate">{item.tunnelName}</p>
              </div>
            </div>
            <Switch size="sm" isSelected={item.serviceRunning} onValueChange={() => toggleService(item)} isDisabled={item.status !== 0 && item.status !== 1} />
          </div>
        </CardHeader>
        <CardBody className="pt-0 pb-3">
          <div className="space-y-2">
            <div className="rounded-lg bg-default-50 px-2 py-1 text-xs">入口：{item.inIp}:{item.inPort}</div>
            <div className="rounded-lg bg-default-50 px-2 py-1 text-xs break-all">目标：{item.remoteAddr}</div>
            <div className="flex gap-2 pt-2">
              <Button size="sm" variant="flat" color="primary" className="flex-1" onPress={() => openEditModal(item)}>编辑</Button>
              <Button size="sm" variant="flat" color="warning" className="flex-1" onPress={() => openCreateModal(item.groupName || "")}>同组新增</Button>
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
          {groupSections.map(section => (
            <Card key={section.key} className="shadow-sm border border-divider overflow-hidden">
              <CardHeader className="pb-3">
                <div className="flex items-center justify-between gap-3 w-full">
                  <div className="min-w-0">
                    <h2 className="text-base font-semibold truncate">{section.name}</h2>
                    <p className="text-xs text-default-500">{section.totalCount} 个转发，{section.runningCount} 个运行中</p>
                  </div>
                  {!section.isUngrouped && (
                    <Button size="sm" variant="flat" color="primary" onPress={() => openCreateModal(section.name)}>+ 新增到此分组</Button>
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
                    {!section.isUngrouped && <Button size="sm" color="primary" variant="flat" onPress={() => openCreateModal(section.name)}>在此分组新增规则</Button>}
                  </div>
                )}
              </CardBody>
            </Card>
          ))}
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
    </div>
  );
}
