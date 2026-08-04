import { useState } from "react";
import { clearToken, getToken } from "./api/client";
import LoginPage from "./pages/LoginPage";
import PolicyListPage from "./pages/PolicyListPage";
import SearchPage from "./pages/SearchPage";
import "./App.css";

export default function App() {
  const [loggedIn, setLoggedIn] = useState(!!getToken());
  const [view, setView] = useState("list"); // "list" | "search"

  function handleLogout() {
    clearToken();
    setLoggedIn(false);
  }

  if (!loggedIn) {
    return <LoginPage onLoggedIn={() => setLoggedIn(true)} />;
  }

  return (
    <>
      <header className="page page-header">
        <nav className="tabs">
          <button
            className={view === "list" ? "tab active" : "tab"}
            onClick={() => setView("list")}
          >
            保单列表
          </button>
          <button
            className={view === "search" ? "tab active" : "tab"}
            onClick={() => setView("search")}
          >
            搜索
          </button>
        </nav>
        <button onClick={handleLogout}>退出登录</button>
      </header>

      {view === "list" ? (
        <PolicyListPage onLogout={handleLogout} />
      ) : (
        <SearchPage onLogout={handleLogout} />
      )}
    </>
  );
}
