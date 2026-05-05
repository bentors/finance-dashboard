package com.bentorangel.finance_dashboard.controller;

import com.bentorangel.finance_dashboard.dto.CategoryRequestDTO;
import com.bentorangel.finance_dashboard.dto.LoginDTO;
import com.bentorangel.finance_dashboard.dto.RegisterDTO;
import com.bentorangel.finance_dashboard.dto.TransactionRequestDTO;
import com.bentorangel.finance_dashboard.model.CategoryType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@DisplayName("TransactionController — Testes de Integração")
class TransactionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule()); // Necessário para serializar LocalDate

    // -----------------------------------------------------------------------
    // Helpers reutilizados por todos os testes
    // -----------------------------------------------------------------------

    /** Registra um novo usuário com email único e devolve o token JWT. */
    private String getAccessToken() throws Exception {
        String email = "user_" + System.currentTimeMillis() + "@teste.com";
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new RegisterDTO("Usuário Teste", email, "senha123"))));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginDTO(email, "senha123"))))
                .andReturn();

        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    /** Cria uma categoria e devolve seu UUID como String. */
    private String criarCategoria(String token, String nome, CategoryType tipo) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CategoryRequestDTO(nome, tipo))))
                .andExpect(status().isCreated())
                .andReturn();

        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    /** Cria uma transação e devolve seu UUID como String. */
    private String criarTransacao(String token, String descricao, BigDecimal valor,
                                  LocalDate data, String categoriaId) throws Exception {
        TransactionRequestDTO dto = new TransactionRequestDTO(
                descricao, valor, data, java.util.UUID.fromString(categoriaId));

        MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn();

        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    // -----------------------------------------------------------------------
    // Segurança — acesso sem token
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Segurança")
    class Seguranca {

        @Test
        @DisplayName("Deve barrar acesso sem token (403 Forbidden)")
        void deveBarrarSemToken() throws Exception {
            mockMvc.perform(get("/api/v1/transactions"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Deve barrar acesso com token inválido (403 Forbidden)")
        void deveBarrarComTokenInvalido() throws Exception {
            mockMvc.perform(get("/api/v1/transactions")
                            .header("Authorization", "Bearer token.invalido.aqui"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Deve barrar acesso com token expirado / adulterado (403 Forbidden)")
        void deveBarrarComTokenAdulterado() throws Exception {
            // JWT com assinatura adulterada
            String tokenAdulterado = "eyJhbGciOiJIUzI1NiJ9"
                    + ".eyJzdWIiOiJoYWNrZXJAdGVzdGUuY29tIn0"
                    + ".assinatura_invalida_aqui";

            mockMvc.perform(get("/api/v1/transactions")
                            .header("Authorization", "Bearer " + tokenAdulterado))
                    .andExpect(status().isForbidden());
        }
    }

    // -----------------------------------------------------------------------
    // CRUD básico
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("CRUD")
    class Crud {

        private String token;
        private String categoriaId;

        @BeforeEach
        void setUp() throws Exception {
            token = getAccessToken();
            categoriaId = criarCategoria(token, "Salário", CategoryType.INCOME);
        }

        @Test
        @DisplayName("Deve criar uma transação com sucesso (201 Created)")
        void deveCriarTransacao() throws Exception {
            TransactionRequestDTO dto = new TransactionRequestDTO(
                    "Salário de Abril",
                    new BigDecimal("5000.00"),
                    LocalDate.of(2026, 4, 5),
                    java.util.UUID.fromString(categoriaId)
            );

            mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.description").value("Salário de Abril"))
                    .andExpect(jsonPath("$.amount").value(5000.00))
                    .andExpect(jsonPath("$.category.id").value(categoriaId))
                    .andExpect(header().string("Location", containsString("/api/v1/transactions/")));
        }

        @Test
        @DisplayName("Deve retornar 400 ao criar transação sem descrição")
        void deveFalharSemDescricao() throws Exception {
            TransactionRequestDTO dto = new TransactionRequestDTO(
                    "",                          // @NotBlank
                    new BigDecimal("100.00"),
                    LocalDate.now(),
                    java.util.UUID.fromString(categoriaId)
            );

            mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        @DisplayName("Deve retornar 400 ao criar transação com valor negativo")
        void deveFalharComValorNegativo() throws Exception {
            TransactionRequestDTO dto = new TransactionRequestDTO(
                    "Transação inválida",
                    new BigDecimal("-50.00"),    // @Positive
                    LocalDate.now(),
                    java.util.UUID.fromString(categoriaId)
            );

            mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Deve retornar 400 ao criar transação com valor zero")
        void deveFalharComValorZero() throws Exception {
            TransactionRequestDTO dto = new TransactionRequestDTO(
                    "Transação zero",
                    BigDecimal.ZERO,            // @Positive exige > 0
                    LocalDate.now(),
                    java.util.UUID.fromString(categoriaId)
            );

            mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Deve buscar uma transação por ID com sucesso (200 OK)")
        void deveBuscarPorId() throws Exception {
            String id = criarTransacao(token, "Freelance", new BigDecimal("1500.00"),
                    LocalDate.of(2026, 4, 1), categoriaId);

            mockMvc.perform(get("/api/v1/transactions/" + id)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.description").value("Freelance"));
        }

        @Test
        @DisplayName("Deve retornar 404 ao buscar transação inexistente")
        void deveFalharAoBuscarIdInexistente() throws Exception {
            String idFalso = "00000000-0000-0000-0000-000000000000";

            mockMvc.perform(get("/api/v1/transactions/" + idFalso)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Deve atualizar uma transação com sucesso (200 OK)")
        void deveAtualizarTransacao() throws Exception {
            String id = criarTransacao(token, "Valor antigo", new BigDecimal("100.00"),
                    LocalDate.of(2026, 3, 1), categoriaId);

            TransactionRequestDTO update = new TransactionRequestDTO(
                    "Valor atualizado",
                    new BigDecimal("250.00"),
                    LocalDate.of(2026, 3, 15),
                    java.util.UUID.fromString(categoriaId)
            );

            mockMvc.perform(put("/api/v1/transactions/" + id)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(update)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value("Valor atualizado"))
                    .andExpect(jsonPath("$.amount").value(250.00));
        }

        @Test
        @DisplayName("Deve deletar uma transação com sucesso (204 No Content)")
        void deveDeletarTransacao() throws Exception {
            String id = criarTransacao(token, "A deletar", new BigDecimal("99.00"),
                    LocalDate.of(2026, 4, 10), categoriaId);

            mockMvc.perform(delete("/api/v1/transactions/" + id)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());

            // Confirma que foi de fato removida (soft-delete)
            mockMvc.perform(get("/api/v1/transactions/" + id)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Deve listar transações paginadas (200 OK)")
        void deveListarTransacoesPaginadas() throws Exception {
            criarTransacao(token, "Tx 1", new BigDecimal("100.00"), LocalDate.of(2026, 4, 1), categoriaId);
            criarTransacao(token, "Tx 2", new BigDecimal("200.00"), LocalDate.of(2026, 4, 2), categoriaId);
            criarTransacao(token, "Tx 3", new BigDecimal("300.00"), LocalDate.of(2026, 4, 3), categoriaId);

            mockMvc.perform(get("/api/v1/transactions")
                            .header("Authorization", "Bearer " + token)
                            .param("size", "2")
                            .param("page", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(2));
        }
    }

    // -----------------------------------------------------------------------
    // Isolamento multiusuário
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Isolamento multiusuário")
    class Isolamento {

        @Test
        @DisplayName("Usuário B não pode ver transações do Usuário A")
        void usuarioBNaoVeTransacoesDoUsuarioA() throws Exception {
            // Usuário A cria uma transação
            String tokenA = getAccessToken();
            String catA = criarCategoria(tokenA, "Renda A", CategoryType.INCOME);
            criarTransacao(tokenA, "Salário secreto", new BigDecimal("9999.00"),
                    LocalDate.of(2026, 4, 1), catA);

            // Usuário B lista as próprias transações — deve vir vazio
            String tokenB = getAccessToken();
            mockMvc.perform(get("/api/v1/transactions")
                            .header("Authorization", "Bearer " + tokenB))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(0));
        }

        @Test
        @DisplayName("Usuário B não pode deletar transação do Usuário A (404)")
        void usuarioBNaoPodeDeletarTransacaoDoUsuarioA() throws Exception {
            String tokenA = getAccessToken();
            String catA = criarCategoria(tokenA, "Renda A", CategoryType.INCOME);
            String idTransacaoA = criarTransacao(tokenA, "Transação do A",
                    new BigDecimal("500.00"), LocalDate.of(2026, 4, 1), catA);

            // Usuário B tenta deletar — deve receber 404 (não encontrado para ele)
            String tokenB = getAccessToken();
            mockMvc.perform(delete("/api/v1/transactions/" + idTransacaoA)
                            .header("Authorization", "Bearer " + tokenB))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Usuário B não pode atualizar transação do Usuário A (404)")
        void usuarioBNaoPodeAtualizarTransacaoDoUsuarioA() throws Exception {
            String tokenA = getAccessToken();
            String catA = criarCategoria(tokenA, "Renda A", CategoryType.INCOME);
            String idTransacaoA = criarTransacao(tokenA, "Transação original",
                    new BigDecimal("500.00"), LocalDate.of(2026, 4, 1), catA);

            String tokenB = getAccessToken();
            String catB = criarCategoria(tokenB, "Renda B", CategoryType.INCOME);

            TransactionRequestDTO tentativa = new TransactionRequestDTO(
                    "Hackeado!", new BigDecimal("1.00"),
                    LocalDate.now(), java.util.UUID.fromString(catB));

            mockMvc.perform(put("/api/v1/transactions/" + idTransacaoA)
                            .header("Authorization", "Bearer " + tokenB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(tentativa)))
                    .andExpect(status().isNotFound());
        }
    }

    // -----------------------------------------------------------------------
    // Busca por período
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Busca por período (/period)")
    class BuscaPorPeriodo {

        @Test
        @DisplayName("Deve retornar apenas transações dentro do período informado")
        void deveRetornarTransacoesDoPeriodo() throws Exception {
            String token = getAccessToken();
            String cat = criarCategoria(token, "Diversos", CategoryType.EXPENSE);

            criarTransacao(token, "Dentro do período", new BigDecimal("100.00"),
                    LocalDate.of(2026, 3, 15), cat);
            criarTransacao(token, "Fora do período — antes", new BigDecimal("200.00"),
                    LocalDate.of(2026, 2, 28), cat);
            criarTransacao(token, "Fora do período — depois", new BigDecimal("300.00"),
                    LocalDate.of(2026, 4, 30), cat);

            mockMvc.perform(get("/api/v1/transactions/period")
                            .header("Authorization", "Bearer " + token)
                            .param("startDate", "2026-03-01")
                            .param("endDate", "2026-03-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].description").value("Dentro do período"));
        }

        @Test
        @DisplayName("Deve retornar 409 quando startDate for posterior a endDate")
        void deveFalharComDatasInvertidas() throws Exception {
            String token = getAccessToken();

            mockMvc.perform(get("/api/v1/transactions/period")
                            .header("Authorization", "Bearer " + token)
                            .param("startDate", "2026-04-30")
                            .param("endDate", "2026-04-01"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("Deve retornar lista vazia quando não houver transações no período")
        void deveRetornarVazioParaPeriodoSemTransacoes() throws Exception {
            String token = getAccessToken();

            mockMvc.perform(get("/api/v1/transactions/period")
                            .header("Authorization", "Bearer " + token)
                            .param("startDate", "2020-01-01")
                            .param("endDate", "2020-01-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(0));
        }
    }

    // -----------------------------------------------------------------------
    // Dashboard Summary
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Dashboard summary (/summary)")
    class DashboardSummary {

        @Test
        @DisplayName("Deve calcular corretamente receitas, despesas e saldo")
        void deveCalcularSummaryCorretamente() throws Exception {
            String token = getAccessToken();
            String catReceita = criarCategoria(token, "Salário", CategoryType.INCOME);
            String catDespesa = criarCategoria(token, "Aluguel", CategoryType.EXPENSE);

            criarTransacao(token, "Salário",  new BigDecimal("5000.00"), LocalDate.of(2026, 4, 1), catReceita);
            criarTransacao(token, "Aluguel",  new BigDecimal("1500.00"), LocalDate.of(2026, 4, 5), catDespesa);
            criarTransacao(token, "Mercado",  new BigDecimal("800.00"),  LocalDate.of(2026, 4, 10), catDespesa);

            mockMvc.perform(get("/api/v1/transactions/summary")
                            .header("Authorization", "Bearer " + token)
                            .param("startDate", "2026-04-01")
                            .param("endDate", "2026-04-30"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalIncome").value(5000.00))
                    .andExpect(jsonPath("$.totalExpense").value(2300.00))
                    .andExpect(jsonPath("$.balance").value(2700.00));
        }

        @Test
        @DisplayName("Deve retornar summary zerado quando não houver transações")
        void deveRetornarSummaryZeradoSemTransacoes() throws Exception {
            String token = getAccessToken();

            mockMvc.perform(get("/api/v1/transactions/summary")
                            .header("Authorization", "Bearer " + token)
                            .param("startDate", "2020-01-01")
                            .param("endDate", "2020-01-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalIncome").value(0))
                    .andExpect(jsonPath("$.totalExpense").value(0))
                    .andExpect(jsonPath("$.balance").value(0));
        }

        @Test
        @DisplayName("Deve retornar 409 quando startDate for posterior a endDate")
        void deveFalharComDatasInvertidas() throws Exception {
            String token = getAccessToken();

            mockMvc.perform(get("/api/v1/transactions/summary")
                            .header("Authorization", "Bearer " + token)
                            .param("startDate", "2026-12-31")
                            .param("endDate", "2026-01-01"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("Usuário B não deve ver o summary do Usuário A")
        void summaryDeveSerIsoladoPorUsuario() throws Exception {
            String tokenA = getAccessToken();
            String catA = criarCategoria(tokenA, "Renda", CategoryType.INCOME);
            criarTransacao(tokenA, "Receita do A", new BigDecimal("10000.00"),
                    LocalDate.of(2026, 4, 1), catA);

            // Usuário B não tem transações — summary deve ser zerado
            String tokenB = getAccessToken();
            mockMvc.perform(get("/api/v1/transactions/summary")
                            .header("Authorization", "Bearer " + tokenB)
                            .param("startDate", "2026-04-01")
                            .param("endDate", "2026-04-30"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalIncome").value(0))
                    .andExpect(jsonPath("$.balance").value(0));
        }
    }

    // -----------------------------------------------------------------------
    // Resumo mensal
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Resumo mensal (/summary/monthly)")
    class ResumoMensal {

        @Test
        @DisplayName("Deve agrupar receitas e despesas por mês corretamente")
        void deveAgruparPorMes() throws Exception {
            String token = getAccessToken();
            String catReceita = criarCategoria(token, "Renda",  CategoryType.INCOME);
            String catDespesa = criarCategoria(token, "Gasto",  CategoryType.EXPENSE);

            // Março
            criarTransacao(token, "Salário março",  new BigDecimal("4000.00"), LocalDate.of(2026, 3, 5), catReceita);
            criarTransacao(token, "Aluguel março",  new BigDecimal("1200.00"), LocalDate.of(2026, 3, 10), catDespesa);
            // Abril
            criarTransacao(token, "Salário abril",  new BigDecimal("4500.00"), LocalDate.of(2026, 4, 5), catReceita);

            mockMvc.perform(get("/api/v1/transactions/summary/monthly")
                            .header("Authorization", "Bearer " + token)
                            .param("year", "2026"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(2)))
                    // Verifica mês 3 (março)
                    .andExpect(jsonPath("$[?(@.month == 3)].income").value(hasItem(4000.0)))
                    .andExpect(jsonPath("$[?(@.month == 3)].expense").value(hasItem(1200.0)))
                    // Verifica mês 4 (abril)
                    .andExpect(jsonPath("$[?(@.month == 4)].income").value(hasItem(4500.0)));
        }

        @Test
        @DisplayName("Deve usar o ano corrente quando o parâmetro year não for informado")
        void deveUsarAnoCorrrenteQuandoYearAusente() throws Exception {
            String token = getAccessToken();

            // Só verificamos que responde 200 — sem transações, lista pode ser vazia
            mockMvc.perform(get("/api/v1/transactions/summary/monthly")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    // -----------------------------------------------------------------------
    // Busca com filtros (/search)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Busca com filtros (/search)")
    class BuscaComFiltros {

        private String token;
        private String catReceita;
        private String catDespesa;

        @BeforeEach
        void setUp() throws Exception {
            token = getAccessToken();
            catReceita = criarCategoria(token, "Renda",   CategoryType.INCOME);
            catDespesa = criarCategoria(token, "Moradia", CategoryType.EXPENSE);

            criarTransacao(token, "Salário mensal",  new BigDecimal("5000.00"), LocalDate.of(2026, 3, 1),  catReceita);
            criarTransacao(token, "Aluguel março",   new BigDecimal("1500.00"), LocalDate.of(2026, 3, 5),  catDespesa);
            criarTransacao(token, "Freelance extra", new BigDecimal("800.00"),  LocalDate.of(2026, 4, 10), catReceita);
        }

        @Test
        @DisplayName("Deve filtrar por descrição (case-insensitive, parcial)")
        void deveFiltrarPorDescricao() throws Exception {
            mockMvc.perform(get("/api/v1/transactions/search")
                            .header("Authorization", "Bearer " + token)
                            .param("description", "salário"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].description").value("Salário mensal"));
        }

        @Test
        @DisplayName("Deve filtrar por tipo INCOME")
        void deveFiltrarPorTipoIncome() throws Exception {
            mockMvc.perform(get("/api/v1/transactions/search")
                            .header("Authorization", "Bearer " + token)
                            .param("type", "INCOME"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2));
        }

        @Test
        @DisplayName("Deve filtrar por tipo EXPENSE")
        void deveFiltrarPorTipoExpense() throws Exception {
            mockMvc.perform(get("/api/v1/transactions/search")
                            .header("Authorization", "Bearer " + token)
                            .param("type", "EXPENSE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].description").value("Aluguel março"));
        }

        @Test
        @DisplayName("Deve filtrar por categoria específica")
        void deveFiltrarPorCategoria() throws Exception {
            mockMvc.perform(get("/api/v1/transactions/search")
                            .header("Authorization", "Bearer " + token)
                            .param("categoryId", catDespesa))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].description").value("Aluguel março"));
        }

        @Test
        @DisplayName("Deve filtrar por período com startDate e endDate")
        void deveFiltrarPorPeriodo() throws Exception {
            mockMvc.perform(get("/api/v1/transactions/search")
                            .header("Authorization", "Bearer " + token)
                            .param("startDate", "2026-03-01")
                            .param("endDate", "2026-03-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2));
        }

        @Test
        @DisplayName("Deve combinar múltiplos filtros corretamente (descrição + tipo + período)")
        void deveCombinarFiltros() throws Exception {
            mockMvc.perform(get("/api/v1/transactions/search")
                            .header("Authorization", "Bearer " + token)
                            .param("description", "salário")
                            .param("type", "INCOME")
                            .param("startDate", "2026-03-01")
                            .param("endDate", "2026-03-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].description").value("Salário mensal"));
        }

        @Test
        @DisplayName("Deve retornar vazio quando nenhuma transação bate com os filtros")
        void deveRetornarVazioSemResultados() throws Exception {
            mockMvc.perform(get("/api/v1/transactions/search")
                            .header("Authorization", "Bearer " + token)
                            .param("description", "descrição que não existe"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(0));
        }
    }

    // -----------------------------------------------------------------------
    // Export CSV
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Export CSV (/export)")
    class ExportCsv {

        @Test
        @DisplayName("Deve exportar CSV com Content-Disposition e Content-Type corretos")
        void deveExportarCsvComHeadersCorretos() throws Exception {
            String token = getAccessToken();
            String cat = criarCategoria(token, "Renda", CategoryType.INCOME);
            criarTransacao(token, "Salário",  new BigDecimal("5000.00"), LocalDate.of(2026, 4, 1), cat);
            criarTransacao(token, "Freelance", new BigDecimal("800.00"),  LocalDate.of(2026, 4, 15), cat);

            mockMvc.perform(get("/api/v1/transactions/export")
                            .header("Authorization", "Bearer " + token)
                            .param("startDate", "2026-04-01")
                            .param("endDate", "2026-04-30"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", containsString("text/csv")))
                    .andExpect(header().string("Content-Disposition", containsString("attachment")))
                    .andExpect(header().string("Content-Disposition", containsString("extrato_")));
        }

        @Test
        @DisplayName("Deve exportar CSV com cabeçalho e dados corretos")
        void deveExportarCsvComDadosCorretos() throws Exception {
            String token = getAccessToken();
            String cat = criarCategoria(token, "Renda", CategoryType.INCOME);
            criarTransacao(token, "Salário Abril", new BigDecimal("3000.00"), LocalDate.of(2026, 4, 5), cat);

            MvcResult result = mockMvc.perform(get("/api/v1/transactions/export")
                            .header("Authorization", "Bearer " + token)
                            .param("startDate", "2026-04-01")
                            .param("endDate", "2026-04-30"))
                    .andExpect(status().isOk())
                    .andReturn();

            String csv = result.getResponse().getContentAsString();
            // Cabeçalho obrigatório
            org.junit.jupiter.api.Assertions.assertTrue(
                    csv.contains("Data;Descricao;Categoria;Tipo;Valor"),
                    "CSV deve conter cabeçalho com os campos corretos"
            );
            // Dados da transação
            org.junit.jupiter.api.Assertions.assertTrue(
                    csv.contains("Salário Abril"),
                    "CSV deve conter a descrição da transação"
            );
            org.junit.jupiter.api.Assertions.assertTrue(
                    csv.contains("3000"),
                    "CSV deve conter o valor da transação"
            );
        }

        @Test
        @DisplayName("Deve retornar CSV vazio (só cabeçalho) quando não houver transações no período")
        void deveExportarCsvVazioSemTransacoes() throws Exception {
            String token = getAccessToken();

            MvcResult result = mockMvc.perform(get("/api/v1/transactions/export")
                            .header("Authorization", "Bearer " + token)
                            .param("startDate", "2020-01-01")
                            .param("endDate", "2020-01-31"))
                    .andExpect(status().isOk())
                    .andReturn();

            String csv = result.getResponse().getContentAsString();
            // Apenas o cabeçalho, sem linhas de dados
            String[] lines = csv.trim().split("\n");
            org.junit.jupiter.api.Assertions.assertEquals(1, lines.length,
                    "CSV sem transações deve conter apenas a linha de cabeçalho");
        }

        @Test
        @DisplayName("Deve retornar 409 quando startDate for posterior a endDate")
        void deveFalharComDatasInvertidas() throws Exception {
            String token = getAccessToken();

            mockMvc.perform(get("/api/v1/transactions/export")
                            .header("Authorization", "Bearer " + token)
                            .param("startDate", "2026-04-30")
                            .param("endDate", "2026-04-01"))
                    .andExpect(status().isConflict());
        }
    }
}