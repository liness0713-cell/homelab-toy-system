import { useEffect, useState } from "react";
import { cancelPolicy, createPolicy, listPolicies } from "../api/client";

const PRODUCT_TYPES = ["TENGAN", "INGURAMU"];

export default function PolicyListPage({ onLogout }) {
  const [policies, setPolicies] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [form, setForm] = useState({ holderName: "", productType: PRODUCT_TYPES[0], premium: "" });
  const [creating, setCreating] = useState(false);

  async function refresh() {
    setLoading(true);
    setError("");
    try {
      const data = await listPolicies();
      setPolicies(data);
    } catch (err) {
      setError(err.message);
      if (err.message.includes("重新登录")) {
        onLogout();
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function handleCreate(e) {
    e.preventDefault();
    setCreating(true);
    setError("");
    try {
      await createPolicy({
        holderName: form.holderName,
        productType: form.productType,
        premium: Number(form.premium),
      });
      setForm({ holderName: "", productType: PRODUCT_TYPES[0], premium: "" });
      await refresh();
    } catch (err) {
      setError(err.message);
    } finally {
      setCreating(false);
    }
  }

  async function handleCancel(id) {
    setError("");
    try {
      await cancelPolicy(id);
      await refresh();
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <div className="page">
      <header className="page-header">
        <h1>保单列表</h1>
        <button onClick={onLogout}>退出登录</button>
      </header>

      <form className="card create-form" onSubmit={handleCreate}>
        <h2>创建保单</h2>
        <label>
          投保人姓名
          <input
            value={form.holderName}
            onChange={(e) => setForm({ ...form, holderName: e.target.value })}
            required
          />
        </label>
        <label>
          产品类型
          <select
            value={form.productType}
            onChange={(e) => setForm({ ...form, productType: e.target.value })}
          >
            {PRODUCT_TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </label>
        <label>
          保费
          <input
            type="number"
            min="0.01"
            step="0.01"
            value={form.premium}
            onChange={(e) => setForm({ ...form, premium: e.target.value })}
            required
          />
        </label>
        <button type="submit" disabled={creating}>
          {creating ? "创建中..." : "创建"}
        </button>
      </form>

      {error && <p className="error">{error}</p>}

      {loading ? (
        <p>加载中...</p>
      ) : (
        <table className="policy-table">
          <thead>
            <tr>
              <th>保单号</th>
              <th>投保人</th>
              <th>产品类型</th>
              <th>保费</th>
              <th>状态</th>
              <th>创建时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {policies.map((p) => (
              <tr key={p.id}>
                <td>{p.policyNo}</td>
                <td>{p.holderName}</td>
                <td>{p.productType}</td>
                <td>{p.premium}</td>
                <td>{p.status}</td>
                <td>{p.createdAt}</td>
                <td>
                  {p.status !== "CANCELLED" && (
                    <button onClick={() => handleCancel(p.id)}>取消</button>
                  )}
                </td>
              </tr>
            ))}
            {policies.length === 0 && (
              <tr>
                <td colSpan={7}>暂无保单</td>
              </tr>
            )}
          </tbody>
        </table>
      )}
    </div>
  );
}
