# DeskDB

Base de datos embebida en Java con API fluida.

## Características

- **API Fluida**: Interfaz intuitiva y fácil de usar
- **Sin dependencias externas**: Solo Java nativo (excepto logging y JSON)
- **Persistencia en archivo único**: Formato `.deskdb` portable
- **Tipado seguro**: Soporte para múltiples tipos de datos

## Instalación

```bash
mvn clean install
```

## Uso Básico

```java
// Abrir/create una base de datos
DeskDB db = DeskDB.open("/ruta/mi.deskdb");

// Insertar datos
db.table("usuarios")
  .insert()
  .value("nombre", "Ana")
  .value("edad", 30)
  .execute();

// Consultar datos
List<Map<String, Object>> resultados = db.table("usuarios")
  .select()
  .where("edad")
  .greaterThan(18)
  .execute();

// Actualizar datos
db.table("usuarios")
  .update()
  .set("edad", 31)
  .where("nombre")
  .equals("Ana")
  .execute();

// Eliminar datos
db.table("usuarios")
  .delete()
  .where("nombre")
  .equals("Ana")
  .execute();

// Cerrar la base de datos
db.close();
```

## Tipos de Datos Soportados

- STRING: Texto UTF-8
- INT: Entero de 4 bytes
- LONG: Entero de 8 bytes
- DOUBLE: Punto flotante de 8 bytes
- BOOLEAN: Valor booleano
- DATE: Fecha (epoch en milisegundos)
- TIMESTAMP: Timestamp con precisión de nanosegundos
- BLOB: Datos binarios
- JSON: Texto JSON

## Estructura del Proyecto

```
com.deskdb
├── core
│   ├── DeskDB.java           # Punto de entrada principal
│   ├── TableOperations.java  # Operaciones CRUD fluidas
│   ├── Filter.java           # Filtros para consultas
│   └── TableSchema.java      # Esquema de tablas
├── storage
│   └── (en desarrollo)       # Almacenamiento en disco
├── query
│   └── (en desarrollo)       # Motor de consultas
└── util
    ├── Serializer.java       # Serialización JSON
    └── Checksum.java         # Utilidades de checksum
```

## Roadmap

- [x] v0.1: CRUD básico con persistencia
- [ ] v0.2: Transacciones ACID + WAL
- [ ] v0.3: Índices B-Tree + filtros avanzados
- [ ] v0.4: Modo servidor
- [ ] v1.0: API completa + documentación
- [ ] v1.1: Soporte SQL
- [ ] v2.0: Multi-modelo (JSON + Relacional)

## Testing

```bash
mvn test
```

## Licencia

MIT License