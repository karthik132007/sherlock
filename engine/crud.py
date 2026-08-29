"""Neo4j Aura CRUD helpers for Sherlock graph data.

The module reads credentials from the repository ``.env`` file when available.
Supported variables are ``NEO4J_URI``/``AURA_URI``/``DB_URI``,
``NEO4J_USERNAME``/``DB_USERNAME``, ``NEO4J_PASSWORD``/``DB_PASSWORD``,
and optional ``NEO4J_DATABASE``/``DB_DATABASE``.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional, Tuple


def _load_dotenv() -> None:
	"""Load simple KEY=VALUE entries without requiring python-dotenv."""
	env_path = Path(__file__).resolve().parent.parent / ".env"
	if not env_path.exists():
		return
	for line in env_path.read_text(encoding="utf-8").splitlines():
		line = line.strip()
		if not line or line.startswith("#") or "=" not in line:
			continue
		key, value = line.split("=", 1)
		key = key.strip()
		value = value.strip().strip('"').strip("'")
		os.environ.setdefault(key, value)


def _first_env(*names: str) -> Optional[str]:
	return next((os.getenv(name) for name in names if os.getenv(name)), None)


def _env_flag(*names: str) -> Optional[bool]:
	value = _first_env(*names)
	if value is None:
		return None
	return value.strip().lower() not in {"0", "false", "no", "off"}


def _driver_class():
	try:
		from neo4j import GraphDatabase
	except ImportError as exc:
		raise RuntimeError("Install the Neo4j Python driver with: pip install neo4j") from exc
	return GraphDatabase


def _json_value(value: Any) -> str:
	return json.dumps(value if value is not None else {}, ensure_ascii=False)


def _node_properties(node: Mapping[str, Any], project_id: str) -> Dict[str, Any]:
	properties = dict(node)
	properties["project_id"] = project_id
	if isinstance(properties.get("data"), (dict, list)):
		properties["data"] = _json_value(properties["data"])
	for key in ("source_files", "chunk_ids", "aliases"):
		if isinstance(properties.get(key), list):
			properties[key] = [str(item) for item in properties[key]]
	return properties


def _entity_id(entity: Mapping[str, Any]) -> str:
	if entity.get("id"):
		return str(entity["id"])
	name = str(entity.get("name", "entity")).strip().lower()
	entity_type = str(entity.get("type", "entity")).strip().lower()
	slug = "_".join(part for part in name.replace("-", " ").split() if part)
	return f"{entity_type}_{slug or 'unknown'}"


def _records_from_json(path: Path, keys: Tuple[str, ...]) -> List[Dict[str, Any]]:
	with path.open("r", encoding="utf-8-sig") as file:
		payload = json.load(file)
	if isinstance(payload, list):
		return [item for item in payload if isinstance(item, dict)]
	if isinstance(payload, dict):
		for key in keys:
			if isinstance(payload.get(key), list):
				return [item for item in payload[key] if isinstance(item, dict)]
	return []


def _find_input_file(data_dir: Path, filename: str) -> Optional[Path]:
	direct = data_dir / filename
	if direct.is_file():
		return direct
	for candidate_dir in (data_dir / "processed", data_dir.parent / "processed"):
		candidate = candidate_dir / filename
		if candidate.is_file():
			return candidate
	return next((path for path in data_dir.rglob(filename) if path.is_file()), None)


def load_graph_files(data_dir: str | Path) -> Tuple[str, List[Dict[str, Any]], List[Dict[str, Any]]]:
	"""Load processed entities and relationships from a case or data directory."""
	root = Path(data_dir).expanduser().resolve()
	entities_path = _find_input_file(root, "entities.json")
	if entities_path is None:
		raise FileNotFoundError(f"entities.json not found in {root}")
	entities = _records_from_json(entities_path, ("entities",))
	with entities_path.open("r", encoding="utf-8-sig") as file:
		entities_payload = json.load(file)
	project_id = entities_path.parent.name
	if entities_path.parent.name == "processed":
		project_id = entities_path.parent.parent.name
	elif entities_path.parent == root:
		project_id = root.name

	relationships: List[Dict[str, Any]] = []
	if isinstance(entities_payload, dict):
		for key in ("relationships", "relations"):
			if isinstance(entities_payload.get(key), list):
				relationships = [item for item in entities_payload[key] if isinstance(item, dict)]
				break
	for filename in ("relations.json", "relationships.json", "graph_data.json"):
		relations_path = entities_path.parent / filename
		if not relationships and relations_path.is_file():
			relationships = _records_from_json(relations_path, ("relationships", "relations", "graph"))
			break
	return project_id, entities, relationships


def _endpoint_id(endpoint: Any, entities_by_key: Mapping[str, Mapping[str, Any]]) -> Optional[str]:
	if isinstance(endpoint, Mapping):
		endpoint_id = endpoint.get("id")
		endpoint_name = endpoint.get("name")
	else:
		endpoint_id = endpoint
		endpoint_name = endpoint
	for value in (endpoint_id, endpoint_name):
		if value is not None and str(value).lower() in entities_by_key:
			return str(entities_by_key[str(value).lower()]["id"])
	return None


def build_relationships(
	entities: List[Dict[str, Any]], relationships: List[Dict[str, Any]]
) -> List[Dict[str, Any]]:
	"""Convert raw relationship endpoints into CRUD-ready entity IDs."""
	entities_by_key: Dict[str, Mapping[str, Any]] = {}
	for entity in entities:
		entity["id"] = _entity_id(entity)
		keys = [entity["id"], entity.get("name", ""), *entity.get("aliases", [])]
		for key in keys:
			if key:
				entities_by_key[str(key).lower()] = entity

	result: List[Dict[str, Any]] = []
	for index, relationship in enumerate(relationships, start=1):
		source_id = _endpoint_id(relationship.get("source"), entities_by_key)
		target_id = _endpoint_id(relationship.get("target"), entities_by_key)
		if not source_id or not target_id:
			continue
		item = dict(relationship)
		item["source"] = {"id": source_id}
		item["target"] = {"id": target_id}
		item.setdefault("relation_id", item.get("id") or f"rel_{index:03d}")
		result.append(item)
	return result


class Neo4jStore:
	"""Small parameterized CRUD wrapper around a Neo4j Aura database."""

	def __init__(
		self,
		uri: Optional[str] = None,
		username: Optional[str] = None,
		password: Optional[str] = None,
		database: Optional[str] = None,
		tls_verify: Optional[bool] = None,
	) -> None:
		_load_dotenv()
		self.uri = uri or _first_env("NEO4J_URI", "AURA_URI", "DB_URI")
		self.username = username or _first_env("NEO4J_USERNAME", "NEO4J_USER", "DB_USERNAME")
		self.password = password or _first_env("NEO4J_PASSWORD", "DB_PASSWORD")
		self.database = database or _first_env("NEO4J_DATABASE", "DB_DATABASE")
		self.tls_verify = tls_verify if tls_verify is not None else _env_flag("NEO4J_TLS_VERIFY")
		missing = [name for name, value in (("NEO4J_URI", self.uri), ("NEO4J_USERNAME", self.username), ("NEO4J_PASSWORD", self.password)) if not value]
		if missing:
			raise ValueError(f"Missing Neo4j settings: {', '.join(missing)}. Add them to .env or pass them explicitly.")
		connection_uri = self.uri
		if self.tls_verify is False and connection_uri.startswith("neo4j+s://"):
			connection_uri = connection_uri.replace("neo4j+s://", "neo4j+ssc://", 1)
		self._driver = _driver_class().driver(connection_uri, auth=(self.username, self.password))

	def verify_connection(self) -> bool:
		self._driver.verify_connectivity()
		return True

	def close(self) -> None:
		self._driver.close()

	def create_node(self, project_id: str, node: Mapping[str, Any]) -> Dict[str, Any]:
		properties = _node_properties(node, project_id)
		query = """
		MERGE (n:Entity {project_id: $project_id, id: $id})
		SET n = $properties
		RETURN n
		"""
		with self._driver.session(database=self.database) as session:
			record = session.run(query, project_id=project_id, id=str(node["id"]), properties=properties).single()
		return dict(record["n"]) if record else {}

	def get_node(self, project_id: str, node_id: str) -> Optional[Dict[str, Any]]:
		query = "MATCH (n:Entity {project_id: $project_id, id: $node_id}) RETURN n"
		with self._driver.session(database=self.database) as session:
			record = session.run(query, project_id=project_id, node_id=node_id).single()
		return dict(record["n"]) if record else None

	def update_node(self, project_id: str, node_id: str, fields: Mapping[str, Any]) -> Optional[Dict[str, Any]]:
		properties = dict(fields)
		if isinstance(properties.get("data"), (dict, list)):
			properties["data"] = _json_value(properties["data"])
		query = """
		MATCH (n:Entity {project_id: $project_id, id: $node_id})
		SET n += $properties
		RETURN n
		"""
		with self._driver.session(database=self.database) as session:
			record = session.run(query, project_id=project_id, node_id=node_id, properties=properties).single()
		return dict(record["n"]) if record else None

	def delete_node(self, project_id: str, node_id: str) -> bool:
		query = "MATCH (n:Entity {project_id: $project_id, id: $node_id}) DETACH DELETE n RETURN count(n) AS deleted"
		with self._driver.session(database=self.database) as session:
			record = session.run(query, project_id=project_id, node_id=node_id).single()
		return bool(record and record["deleted"])

	def create_relationship(self, project_id: str, relationship: Mapping[str, Any]) -> Dict[str, Any]:
		source = relationship.get("source", {})
		target = relationship.get("target", {})
		source_id = source.get("id") if isinstance(source, Mapping) else source
		target_id = target.get("id") if isinstance(target, Mapping) else target
		if not source_id or not target_id or not relationship.get("relation_id"):
			raise ValueError("A relationship requires relation_id, source.id, and target.id")
		properties = {key: value for key, value in relationship.items() if key not in ("source", "target")}
		properties["project_id"] = project_id
		query = """
		MATCH (source:Entity {project_id: $project_id, id: $source_id})
		MATCH (target:Entity {project_id: $project_id, id: $target_id})
		MERGE (source)-[r:RELATED {project_id: $project_id, relation_id: $relation_id}]->(target)
		SET r = $properties
		RETURN r, source.id AS source_id, target.id AS target_id
		"""
		with self._driver.session(database=self.database) as session:
			record = session.run(query, project_id=project_id, source_id=source_id, target_id=target_id, relation_id=relationship["relation_id"], properties=properties).single()
		if not record:
			raise ValueError(f"Relationship endpoints not found: {source_id} -> {target_id}")
		result = dict(record["r"])
		result.update(source=record["source_id"], target=record["target_id"])
		return result

	def get_graph(self, project_id: str) -> Dict[str, List[Dict[str, Any]]]:
		node_query = "MATCH (n:Entity {project_id: $project_id}) RETURN n ORDER BY n.id"
		relation_query = """
		MATCH (source:Entity {project_id: $project_id})-[r:RELATED {project_id: $project_id}]->(target:Entity {project_id: $project_id})
		RETURN r, source.id AS source, target.id AS target ORDER BY r.relation_id
		"""
		with self._driver.session(database=self.database) as session:
			nodes = [dict(record["n"]) for record in session.run(node_query, project_id=project_id)]
			relations = []
			for record in session.run(relation_query, project_id=project_id):
				relation = dict(record["r"])
				relation.update(source=record["source"], target=record["target"])
				relations.append(relation)
		return {"entities": nodes, "graph": relations}

	def upsert_graph(self, project_id: str, graph_payload: Mapping[str, Any]) -> Dict[str, int]:
		entities = graph_payload.get("entities", [])
		mappings = graph_payload.get("graph", [])
		for entity in entities:
			self.create_node(project_id, entity)
		for mapping in mappings:
			self.create_relationship(project_id, mapping)
		return {"entities": len(entities), "relationships": len(mappings)}

	def load_and_insert(self, data_dir: str | Path, project_id: Optional[str] = None) -> Dict[str, Any]:
		"""Read entities/relations JSON from data_dir and insert them into Aura."""
		loaded_project, entities, relationships = load_graph_files(data_dir)
		effective_project = project_id or loaded_project
		for entity in entities:
			entity["id"] = _entity_id(entity)
			self.create_node(effective_project, entity)
		mappings = build_relationships(entities, relationships)
		for mapping in mappings:
			self.create_relationship(effective_project, mapping)
		return {
			"project_id": effective_project,
			"entities": len(entities),
			"relationships": len(mappings),
		}

	def delete_project(self, project_id: str) -> int:
		query = "MATCH (n {project_id: $project_id}) DETACH DELETE n RETURN count(n) AS deleted"
		with self._driver.session(database=self.database) as session:
			record = session.run(query, project_id=project_id).single()
		return int(record["deleted"]) if record else 0


def connect() -> Neo4jStore:
	"""Create a store from the local environment."""
	return Neo4jStore()


def sync_graph_to_neo4j(
	project_path: str | Path,
	entities: List[Dict[str, Any]],
	mappings: List[Dict[str, Any]],
	verbose: bool = True,
) -> bool:
	"""Persist generated Sherlock graph JSON into Neo4j for a single project.

	This is called automatically after graph_data.json is written so the DB stays in sync
	with the JSON export without requiring a manual trigger.
	"""
	project_dir = Path(project_path).expanduser().resolve()
	project_id = project_dir.name
	store = None
	try:
		store = Neo4jStore()
		store.verify_connection()
		if verbose:
			print(f"[Sherlock Neo4j] Syncing project '{project_id}' into Neo4j")
		store.delete_project(project_id)
		for entity in entities:
			store.create_node(project_id, entity)
		for mapping in mappings:
			store.create_relationship(project_id, mapping)
		if verbose:
			print(f"[Sherlock Neo4j] Synced {len(entities)} entities and {len(mappings)} relationships for '{project_id}'")
		return True
	except Exception as exc:
		if verbose:
			print(f"[Sherlock Neo4j] Sync skipped or failed: {exc}")
		return False
	finally:
		if store is not None:
			try:
				store.close()
			except Exception:
				pass


def main() -> None:
	parser = argparse.ArgumentParser(description="Load Sherlock entities and relationships into Neo4j Aura")
	parser.add_argument("--data", help="Directory containing entities.json and optional relations.json")
	parser.add_argument("--project", help="Override the project id used for inserted records")
	args = parser.parse_args()
	store = connect()
	try:
		store.verify_connection()
		print(f"Connected to Neo4j database '{store.database or 'Aura home database'}'")
		if not args.data:
			parser.error("--data is required")
		result = store.load_and_insert(args.data, args.project)
		print(f"project={result['project_id']} entities={result['entities']} relationships={result['relationships']}")
	finally:
		store.close()


if __name__ == "__main__":
	main()
