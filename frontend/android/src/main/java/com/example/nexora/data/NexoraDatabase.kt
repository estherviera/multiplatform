package com.example.nexora.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Patrón Singleton: una sola instancia de la base de datos en toda la app
@Database(
    entities = [
        Usuario::class,
        Video::class,
        Empresa::class,
        Curso::class,
        Leccion::class,
        Recurso::class,
        Publicacion::class
    ],
    version = 2,
    exportSchema = false
)
abstract class NexoraDatabase : RoomDatabase() {

    abstract fun usuarioDao(): UsuarioDao
    abstract fun videoDao(): VideoDao
    abstract fun empresaDao(): EmpresaDao
    abstract fun cursoDao(): CursoDao
    abstract fun leccionDao(): LeccionDao
    abstract fun recursoDao(): RecursoDao
    abstract fun publicacionDao(): PublicacionDao

    companion object {
        @Volatile
        private var INSTANCE: NexoraDatabase? = null

        fun getInstance(context: Context): NexoraDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NexoraDatabase::class.java,
                    "nexora_database"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(PrepoblarCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// Callback que inserta los datos de demo la primera vez que se crea la BD
private class PrepoblarCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        INSTANCE_REF?.let { database ->
            CoroutineScope(Dispatchers.IO).launch {
                prepoblar(database)
            }
        }
    }
}

private var INSTANCE_REF: NexoraDatabase? = null

// Se llama desde NexoraRepository para guardar la referencia
fun registrarInstancia(db: NexoraDatabase) {
    INSTANCE_REF = db
}

// Datos de demostración — los mismos del prototipo HTML
private suspend fun prepoblar(db: NexoraDatabase) {
    // === EMPRESAS ===
    db.empresaDao().insertar(
        listOf(
            Empresa("pixel", "Pixel Academy", "Diseño y creatividad",
                "Aprende diseño gráfico, UX/UI y creación de contenido digital.",
                "✳", "6854EF,B08EFF", 1240),
            Empresa("code", "CodeLab Studio", "Tecnología",
                "Cursos prácticos de programación, desarrollo web e inteligencia artificial.",
                "⌘", "1E9D9F,5AD8BC", 870),
            Empresa("growth", "Growth Business", "Negocios y marketing",
                "Estrategias para emprender, hacer crecer tu marca y vender más.",
                "↗", "F09B62,EF626D", 560)
        )
    )

    // === CURSOS ===
    db.cursoDao().insertar(
        listOf(
            Curso(1, "pixel", "Fundamentos de diseño UX/UI",
                "Del boceto al prototipo profesional.", "🎨", "B9A7FF,6E65D6", 50),
            Curso(2, "pixel", "Diseño visual para marcas",
                "Colores, tipografía y lenguaje visual.", "✏️", "FFA78B,FFCDAD", 25),
            Curso(3, "code", "Desarrollo web desde cero",
                "HTML, CSS y JavaScript con proyectos reales.", "💻", "51C8C3,216C8C", 75),
            Curso(4, "code", "Introducción a la IA",
                "Conceptos, herramientas y casos de uso.", "🤖", "3477B4,75BDFF", 0),
            Curso(5, "growth", "Marketing digital estratégico",
                "Campañas, audiencias y análisis de resultados.", "📈", "FFB46F,E86F62", 35),
            Curso(6, "growth", "Emprender paso a paso",
                "De la idea al primer cliente.", "🚀", "ED6D91,A94C9F", 0)
        )
    )

    // === LECCIONES ===
    db.leccionDao().insertar(
        listOf(
            // Curso 1 - UX/UI
            Leccion(cursoId = 1, titulo = "Introducción al diseño UX", orden = 1, completada = true),
            Leccion(cursoId = 1, titulo = "Investigación de usuarios", orden = 2, completada = true),
            Leccion(cursoId = 1, titulo = "Wireframes y prototipos", orden = 3),
            Leccion(cursoId = 1, titulo = "Diseño de interfaces", orden = 4),
            // Curso 2 - Diseño visual
            Leccion(cursoId = 2, titulo = "Principios visuales", orden = 1, completada = true),
            Leccion(cursoId = 2, titulo = "Paletas y tipografía", orden = 2),
            Leccion(cursoId = 2, titulo = "Aplicaciones de marca", orden = 3),
            // Curso 3 - Desarrollo web
            Leccion(cursoId = 3, titulo = "HTML básico", orden = 1, completada = true),
            Leccion(cursoId = 3, titulo = "CSS moderno", orden = 2, completada = true),
            Leccion(cursoId = 3, titulo = "JavaScript práctico", orden = 3, completada = true),
            Leccion(cursoId = 3, titulo = "Proyecto final", orden = 4),
            // Curso 4 - IA
            Leccion(cursoId = 4, titulo = "Qué es la inteligencia artificial", orden = 1),
            Leccion(cursoId = 4, titulo = "Modelos de lenguaje", orden = 2),
            Leccion(cursoId = 4, titulo = "Aplicaciones prácticas", orden = 3),
            // Curso 5 - Marketing
            Leccion(cursoId = 5, titulo = "Estrategia digital", orden = 1, completada = true),
            Leccion(cursoId = 5, titulo = "Redes sociales", orden = 2),
            Leccion(cursoId = 5, titulo = "Métricas y campañas", orden = 3),
            // Curso 6 - Emprender
            Leccion(cursoId = 6, titulo = "Validar una idea", orden = 1),
            Leccion(cursoId = 6, titulo = "Modelo de negocio", orden = 2),
            Leccion(cursoId = 6, titulo = "Lanzamiento", orden = 3)
        )
    )

    // === RECURSOS ===
    db.recursoDao().insertar(
        listOf(
            Recurso(empresaId = "pixel", titulo = "Guía práctica de diseño UX", tipo = "PDF", tamano = "2,4 MB"),
            Recurso(empresaId = "pixel", titulo = "Plantilla de investigación", tipo = "PDF", tamano = "1,1 MB"),
            Recurso(empresaId = "code", titulo = "Ejercicios de JavaScript", tipo = "PDF", tamano = "1,7 MB"),
            Recurso(empresaId = "code", titulo = "Código de ejemplo", tipo = "ZIP", tamano = "3,2 MB"),
            Recurso(empresaId = "growth", titulo = "Checklist de marketing", tipo = "PDF", tamano = "860 KB"),
            Recurso(empresaId = "growth", titulo = "Plantilla de plan de negocio", tipo = "PDF", tamano = "1,5 MB")
        )
    )

    // === PUBLICACIONES ===
    db.publicacionDao().insertar(
        listOf(
            Publicacion(empresaId = "pixel", autor = "Lucía · Administradora",
                titulo = "¡Bienvenidos a Pixel Academy!",
                texto = "Comparte tus proyectos, haz preguntas y aprende con nuestra comunidad.",
                likes = 24),
            Publicacion(empresaId = "code", autor = "Daniel · Profesor",
                titulo = "Reto de programación de la semana",
                texto = "Os proponemos crear una pequeña página web responsive. ¡Compartid vuestros resultados!",
                likes = 18),
            Publicacion(empresaId = "growth", autor = "Sofía · Administradora",
                titulo = "Nuevo recurso disponible",
                texto = "Ya podéis consultar nuestra guía para planificar una campaña de marketing digital.",
                likes = 12)
        )
    )
}
