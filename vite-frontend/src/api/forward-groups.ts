import Network from "./network";

export interface ForwardGroupRecord {
  id?: number;
  name: string;
  remark?: string;
  createdTime?: number;
  updatedTime?: number;
  status?: number;
}

export const getForwardGroupList = () => Network.post<ForwardGroupRecord[]>("/forward/group/list");
export const createForwardGroup = (data: { name: string }) => Network.post("/forward/group/create", data);
export const updateForwardGroupRecord = (data: { id: number; name: string }) => Network.post("/forward/group/update", data);
export const deleteForwardGroupRecord = (id: number) => Network.post("/forward/group/delete", { id });
export const batchUpdateForwardGroup = (data: { ids: number[]; groupName: string }) => Network.post("/forward/batch-update-group", data);
export const batchDeleteForwards = (ids: number[]) => Network.post("/forward/batch-delete", { ids });
