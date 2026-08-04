import { useState } from "react";
import { searchPolicies } from "../api/client";

export default function SearchPage({ onLogout }) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [searched, setSearched] = useState(false);

  async function handleSearch(e) {
    e.preventDefault();
    setLoading(true);
    setError("");
    try {
      const data = await searchPolicies(query);
      setResults(data);
      setSearched(true);
    } catch (err) {
      setError(err.message);
      if (err.message.includes("重新登录")) {
        onLogout();
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="page">
      <form className="card create-form" onSubmit={handleSearch}>
        <label>
          搜索（投保人姓名 / 保单号 / 产品类型）
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="比如：山田 或 POL-XXXX 或 TENGAN"
          />
        </label>
        <button type="submit" disabled={loading}>
          {loading ? "搜索中..." : "搜索"}
        </button>
      </form>

      <p className="hint">这个搜索走的是 search-service（Elasticsearch），不是 policy-service 的MySQL——CQRS的读路径。</p>

      {error && <p className="error">{error}</p>}

      {searched && (
        <table className="policy-table">
          <thead>
            <tr>
              <th>保单号</th>
              <th>投保人</th>
              <th>产品类型</th>
              <th>保费</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            {results.map((p) => (
              <tr key={p.id}>
                <td>{p.policyNo}</td>
                <td>{p.holderName}</td>
                <td>{p.productType}</td>
                <td>{p.premium}</td>
                <td>{p.status}</td>
              </tr>
            ))}
            {results.length === 0 && (
              <tr>
                <td colSpan={5}>没有匹配的保单</td>
              </tr>
            )}
          </tbody>
        </table>
      )}
    </div>
  );
}
