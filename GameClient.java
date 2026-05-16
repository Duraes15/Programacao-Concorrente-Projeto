import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


public class GameClient extends JFrame {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    
    private LoginPanel loginPanel;
    private GamePanel gamePanel;
    private MenuPanel menuPanel;
    private RankingPanel rankingPanel;

    private StringBuilder rankBuffer = new StringBuilder();
    private boolean collectingRankings = false;

    private Map<String, GameObject> gameObjectsMap = new ConcurrentHashMap<>();

    private String myUsername;

    // FIX: estado da fila guardado no GameClient, acessível a todas as classes internas.
    // Antes era calculado a partir do estado do menuPanel na thread do socket
    // (race condition). Agora é atualizado diretamente nos action listeners.
    private boolean estavaNaFila = false;

    public GameClient() {
        initGUI();
        SwingUtilities.invokeLater(() -> conectarServidor());
    }

    private void initGUI() {
        setTitle("Mini-Jogo Concorrente");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setVisible(true);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        loginPanel = new LoginPanel();
        getContentPane().add(loginPanel);
        revalidate();
        repaint();
    }

    private void conectarServidor() {
        new Thread(() -> {
            try {
                socket = new Socket("127.0.0.1", 12345);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);
                String line;
                while ((line = in.readLine()) != null) {
                    processarMensagem(line);
                }
            } catch (IOException e) {
                System.out.println("Erro de conexão: " + e.getMessage());
            }
        }).start();
    }

    private void processarMensagem(String msg) {
        if (msg.equals("RANK_START")) {
            collectingRankings = true;
            rankBuffer.setLength(0);
            return;
        }
        if (msg.equals("RANK_END")) {
            collectingRankings = false;
            // FIX: lê estavaNaFila do campo do GameClient, não do estado do menuPanel.
            // O campo é atualizado na EDT pelos action listeners — sem race condition.
            boolean voltarParaFila = estavaNaFila;
            SwingUtilities.invokeLater(() -> mudarParaRanking(rankBuffer.toString(), voltarParaFila));
            return;
        }
        if (collectingRankings) {
            rankBuffer.append(msg).append("\n");
            return;
        }

        if (msg.contains("Sucesso: Ola")) {
            SwingUtilities.invokeLater(() -> mudarParaMenu());
        } else if (msg.contains("A partida vai comecar")) {
            // Quando a partida começa, o jogador já não está na fila
            estavaNaFila = false;
            SwingUtilities.invokeLater(() -> mudarParaJogo());
        } else if (msg.startsWith("DATA") && gamePanel != null) {
            gamePanel.updateWorld(msg);
        } else if (msg.contains("FIM")) {
            if (gamePanel != null) {
                gamePanel.stopInput();
                gamePanel = null;
            }
            SwingUtilities.invokeLater(() -> mudarParaMenu());
        } else {
            SwingUtilities.invokeLater(() -> {
                if (menuPanel != null && menuPanel.isShowing()) {
                    menuPanel.setInfo(msg);
                } else if (loginPanel != null && loginPanel.isShowing()) {
                    loginPanel.setStatus(msg);
                }
            });
        }
    }

    private void mudarParaRanking(String dados, boolean voltarParaFila) {
        getContentPane().removeAll();
        rankingPanel = new RankingPanel(voltarParaFila);
        rankingPanel.setTexto(dados);
        add(rankingPanel);
        revalidate();
        repaint();
    }

    private void mudarParaMenu() {
        estavaNaFila = false;
        getContentPane().removeAll();
        menuPanel = new MenuPanel();
        add(menuPanel);
        revalidate();
        repaint();
    }

    private void mudarParaJogo() {
        getContentPane().removeAll();
        gamePanel = new GamePanel();
        add(gamePanel);
        revalidate();
        repaint();
    }

    private void mudarParaLogin() {
        estavaNaFila = false;
        getContentPane().removeAll();
        loginPanel = new LoginPanel();
        add(loginPanel);
        revalidate();
        repaint();
    }

    // FIX: extraído para método próprio para ser chamado tanto pelo botão "Voltar"
    // do RankingPanel (quando estava na fila) como pelo MenuPanel normal.
    private void mudarParaMenuComFila() {
        getContentPane().removeAll();
        menuPanel = new MenuPanel();
        // Restaura o estado visual — o servidor ainda tem este jogador na fila
        menuPanel.playBtn.setEnabled(false);
        menuPanel.readyBtn.setVisible(true);
        menuPanel.rankBtn.setVisible(true);
        menuPanel.setInfo("Ainda estás na fila. Clica em 'Estou Pronto' para jogar.");
        // FIX: add() e revalidate() chamados no GameClient (JFrame), não no JPanel
        GameClient.this.add(menuPanel);
        GameClient.this.revalidate();
        GameClient.this.repaint();
    }

    // --- SUB-ECRÃS ---

    class LoginPanel extends JPanel {
        JTextField userField = new JTextField(10);
        JPasswordField passField = new JPasswordField(10);
        JButton loginBtn = new JButton("Login");
        JButton registerBtn = new JButton("Registar");
        JButton cancelBtn = new JButton("Apagar Conta");
        JLabel statusLabel = new JLabel("Bem-vindo!");

        public LoginPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            JPanel f = new JPanel();
            f.add(new JLabel("User:")); f.add(userField);
            f.add(new JLabel("Pass:")); f.add(passField);
            JPanel b = new JPanel();
            b.add(loginBtn); b.add(registerBtn); b.add(cancelBtn);
            add(f); add(b); add(statusLabel);

            loginBtn.addActionListener(e -> enviar("login"));
            registerBtn.addActionListener(e -> enviar("register"));
            cancelBtn.addActionListener(e -> enviar("cancel"));
        }

        private void enviar(String cmd) {
            if (cmd.equals("login") || cmd.equals("register")) {
                GameClient.this.myUsername = userField.getText().trim();
            }
            out.println(cmd + "," + userField.getText() + "," + new String(passField.getPassword()));
        }

        public void setStatus(String msg) { statusLabel.setText(msg); }
    }

    class MenuPanel extends JPanel {
        JButton playBtn  = new JButton("Procurar Partida");
        JButton readyBtn = new JButton("ESTOU PRONTO");
        JButton rankBtn  = new JButton("Ver Rankings");
        JButton logoutBtn = new JButton("Sair/Logout");
        JLabel infoLabel = new JLabel("Escolhe uma opção para continuar.", SwingConstants.CENTER);

        public MenuPanel() {
            setLayout(new GridLayout(5, 1, 10, 10));
            setBorder(BorderFactory.createEmptyBorder(20, 50, 20, 50));
            infoLabel.setFont(new Font("Arial", Font.BOLD, 14));

            readyBtn.setVisible(false);
            rankBtn.setVisible(false);
            readyBtn.setBackground(Color.ORANGE);

            add(infoLabel);
            add(playBtn);
            add(readyBtn);
            add(rankBtn);
            add(logoutBtn);

            playBtn.addActionListener(e -> {
                out.println("1");
                // FIX: atualiza o campo do GameClient ao entrar na fila
                estavaNaFila = true;
                playBtn.setEnabled(false);
                readyBtn.setVisible(true);
                rankBtn.setVisible(true); 
                infoLabel.setText("Estás na fila. Clica em 'Estou Pronto' para jogar.");
            });

            readyBtn.addActionListener(e -> {
                out.println("READY");
                // FIX: após enviar READY o jogador já não "volta à fila" se ver rankings
                estavaNaFila = false;
                readyBtn.setEnabled(false);
                readyBtn.setText("AGUARDANDO...");
                rankBtn.setVisible(false);
            });

            rankBtn.addActionListener(e -> {
                out.println("2");
                infoLabel.setText("A carregar rankings...");
            });

            logoutBtn.addActionListener(e -> {
                out.println("3");
                mudarParaLogin();
            });
        }

        public void setInfo(String txt) {
            String formattedTxt = "<html><body style='text-align:center;'>" +
                                  txt.replace("\n", "<br>") +
                                  "</body></html>";
            infoLabel.setText(formattedTxt);
        }
    }

    class GameObject {
        String type, id;
        double x, y, angulo, massa;
        int objectType;
        int score;

        GameObject(String id, double x, double y, double angulo, double massa, int score) {
            this.type = "P"; this.id = id; this.x = x; this.y = y;
            this.angulo = angulo; this.massa = massa; this.score = score;
        }

        GameObject(String id, double x, double y, int objType, double tam) {
            this.type = "O"; this.id = id; this.x = x; this.y = y;
            this.objectType = objType; this.massa = tam;
        }
    }

    class GamePanel extends JPanel {
        private volatile int tempoRestante = 120;
        private volatile String lastWorldState = null;
        private final Set<Integer> keysPressed = new HashSet<>();
        private Timer inputTimer;
        private Timer renderTimer;

        public GamePanel() {
            setFocusable(true);
            requestFocusInWindow();

            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e)  { keysPressed.add(e.getKeyCode()); }
                @Override public void keyReleased(KeyEvent e) { keysPressed.remove(e.getKeyCode()); }
            });

            inputTimer = new Timer(30, e -> {
                if (keysPressed.contains(KeyEvent.VK_UP))     out.println("UP");
                if (keysPressed.contains(KeyEvent.VK_LEFT))   out.println("LEFT");
                if (keysPressed.contains(KeyEvent.VK_RIGHT))  out.println("RIGHT");
                if (keysPressed.contains(KeyEvent.VK_ESCAPE)) out.println("ESC");
            });
            inputTimer.start();

            renderTimer = new Timer(30, e -> {
                String state = lastWorldState;
                if (state != null) {
                    parseWorld(state);
                    repaint();
                }
            });
            renderTimer.start();
        }

        public void updateWorld(String msg) {
            lastWorldState = msg;
        }

        private void parseWorld(String msg) {
            try {
                gameObjectsMap.clear();
                String[] parts = msg.split(",");
                for (int i = 1; i < parts.length; i++) {
                    String[] data = parts[i].split(":");
                    String category = data[0].trim();
                    if (category.equals("P")) {
                        String user = data[1].trim();
                        int score = data.length > 6 ? Integer.parseInt(data[6].trim()) : 0;
                        gameObjectsMap.put(user, new GameObject(user,
                            Double.parseDouble(data[2]),
                            Double.parseDouble(data[3]),
                            Double.parseDouble(data[4]),
                            Double.parseDouble(data[5]),
                            score));
                    } else if (category.equals("O")) {
                        String id = "OBJ_" + data[1].trim();
                        gameObjectsMap.put(id, new GameObject(id,
                            Double.parseDouble(data[2]),
                            Double.parseDouble(data[3]),
                            Integer.parseInt(data[4]),
                            Double.parseDouble(data[5])));
                    } else if (category.equals("T")) {
                        tempoRestante = Integer.parseInt(data[1].trim());
                    }
                }
            } catch (Exception e) {
                System.out.println("Erro no parser: " + e.getMessage());
            }
        }

        public void stopInput() {
            if (inputTimer  != null) inputTimer.stop();
            if (renderTimer != null) renderTimer.stop();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(Color.LIGHT_GRAY);
            g2.fillRect(0, 0, getWidth(), getHeight());

            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, 1920, 1080);

            g2.setColor(Color.DARK_GRAY);
            g2.setStroke(new BasicStroke(5));
            g2.drawRect(0, 0, 1905, 985);

            gameObjectsMap.values().forEach(obj -> {
                int ix = (int) obj.x;
                int iy = (int) obj.y;

                if (obj.type.equals("P")) {
                    int radius = (int) Math.sqrt(obj.massa * 20);
                    g2.setStroke(new BasicStroke(3));

                    if (myUsername != null && obj.id.equals(myUsername)) g2.setColor(Color.BLUE);
                    else g2.setColor(Color.RED);
                    g2.drawOval(ix - radius, iy - radius, radius * 2, radius * 2);

                    g2.setColor(Color.BLACK);
                    g2.fillOval(ix - radius, iy - radius, radius * 2, radius * 2);

                    g2.setColor(Color.WHITE);
                    int targetX = (int)(ix + Math.cos(obj.angulo) * radius);
                    int targetY = (int)(iy + Math.sin(obj.angulo) * radius);
                    g2.drawLine(ix, iy, targetX, targetY);

                    FontMetrics fm = g2.getFontMetrics();
                    String labelNome  = obj.id;
                    String labelScore = "⚡ " + obj.score;

                    int nomeW  = fm.stringWidth(labelNome);
                    int scoreW = fm.stringWidth(labelScore);

                    int pad    = 3;
                    int labelX = ix - Math.max(nomeW, scoreW) / 2 - pad;
                    int labelY = iy - radius - fm.getHeight() * 2 - pad * 2 - 4;
                    int labelW = Math.max(nomeW, scoreW) + pad * 2;
                    int labelH = fm.getHeight() * 2 + pad * 2 + 2;

                    g2.setColor(new Color(0, 0, 0, 140));
                    g2.fillRoundRect(labelX, labelY, labelW, labelH, 6, 6);

                    g2.setColor(Color.WHITE);
                    g2.drawString(labelNome, ix - nomeW / 2, iy - radius - fm.getHeight() - pad - 2);

                    g2.setColor(new Color(255, 215, 0));
                    g2.drawString(labelScore, ix - scoreW / 2, iy - radius - pad);

                } else if (obj.type.equals("O")) {
                    int radius = (int) obj.massa;
                    if (obj.objectType == 1) g2.setColor(Color.GREEN);
                    else g2.setColor(Color.RED);
                    g2.fillOval(ix - radius, iy - radius, radius * 2, radius * 2);
                }
            });

            // Timer
            int mins = tempoRestante / 60;
            int segs = tempoRestante % 60;
            String timerStr = String.format("%d:%02d", mins, segs);

            Font timerFont = new Font("Arial", Font.BOLD, 36);
            g2.setFont(timerFont);
            FontMetrics fm = g2.getFontMetrics();
            int timerW = fm.stringWidth(timerStr);

            int tx = 960 - timerW / 2 - 10;
            g2.setColor(new Color(0, 0, 0, 160));
            g2.fillRoundRect(tx, 12, timerW + 20, fm.getHeight() + 10, 10, 10);

            // Vermelho nos últimos 30 segundos
            g2.setColor(tempoRestante <= 30 ? new Color(255, 80, 80) : Color.WHITE);
            g2.drawString(timerStr, tx + 10, 12 + fm.getAscent() + 5);

            g2.setFont(getFont());
        }
    }

    class RankingPanel extends JPanel {
    JButton backBtn = new JButton("Voltar ao menu");
    private Font spaceGrotesk;

    public RankingPanel(boolean voltarParaFila) {
        setLayout(new BorderLayout(0, 0));
        setBorder(BorderFactory.createEmptyBorder(32, 40, 24, 40));

        // Carrega a fonte — faz download uma vez e guarda em /fonts/SpaceGrotesk.ttf
        // ou usa Arial como fallback se não estiver disponível
        try {
            spaceGrotesk = Font.createFont(Font.TRUETYPE_FONT,
                new java.io.File("fonts/SpaceGrotesk-Regular.ttf")).deriveFont(14f);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(spaceGrotesk);
        } catch (Exception e) {
            spaceGrotesk = new Font("Arial", Font.PLAIN, 14);
        }

        // Cabeçalho
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 20, 0));

        JLabel titulo = new JLabel("Rankings", SwingConstants.CENTER);
        titulo.setFont(spaceGrotesk.deriveFont(Font.BOLD, 22f));
        titulo.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel sub = new JLabel("vitórias desde que o servidor arrancou", SwingConstants.CENTER);
        sub.setFont(spaceGrotesk.deriveFont(13f));
        sub.setForeground(new Color(130, 130, 120));
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);

        header.add(titulo);
        header.add(Box.createVerticalStrut(4));
        header.add(sub);

        // Lista
        JPanel lista = new JPanel();
        lista.setLayout(new BoxLayout(lista, BoxLayout.Y_AXIS));
        lista.setOpaque(false);

        // Botão
        backBtn.setFont(spaceGrotesk.deriveFont(Font.PLAIN, 14f));
        backBtn.setBackground(null);
        backBtn.setOpaque(false);
        backBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        backBtn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(200, 200, 190), 1, true),
            BorderFactory.createEmptyBorder(10, 20, 10, 20)));

        add(header, BorderLayout.NORTH);
        add(lista,  BorderLayout.CENTER);
        add(backBtn, BorderLayout.SOUTH);

        backBtn.addActionListener(e -> {
            if (voltarParaFila) mudarParaMenuComFila();
            else mudarParaMenu();
        });
    }

    public void setTexto(String txt) {
        JPanel lista = (JPanel) ((BorderLayout) getLayout())
                                 .getLayoutComponent(BorderLayout.CENTER);
        lista.removeAll();

        if (txt.isBlank() || txt.contains("Ainda nao")) {
            JLabel vazio = new JLabel("Sem recordes ainda.", SwingConstants.CENTER);
            vazio.setFont(spaceGrotesk.deriveFont(14f));
            vazio.setForeground(new Color(160, 160, 150));
            vazio.setAlignmentX(Component.CENTER_ALIGNMENT);
            lista.add(Box.createVerticalStrut(30));
            lista.add(vazio);
        } else {
            String[] linhas = txt.strip().split("\n");
            for (int i = 0; i < linhas.length; i++) {
                String linha = linhas[i].trim();
                if (linha.isEmpty()) continue;
                lista.add(criarEntrada(linha, i + 1));
            }
        }

        lista.revalidate();
        lista.repaint();
    }

    private JPanel criarEntrada(String linha, int pos) {
        // Formato: "username: N vitorias"
        String[] partes = linha.split(":");
        String nome    = partes.length > 0 ? partes[0].trim() : linha;
        String vitorias = partes.length > 1 ? partes[1].trim() : "";

        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createMatteBorder(
            0, 0, 1, 0, new Color(220, 218, 210)));  // linha divisória em baixo
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        row.setPreferredSize(new Dimension(0, 44));

        JLabel posLabel = new JLabel(String.valueOf(pos), SwingConstants.RIGHT);
        posLabel.setFont(spaceGrotesk.deriveFont(13f));
        posLabel.setForeground(new Color(160, 160, 150));
        posLabel.setPreferredSize(new Dimension(20, 20));

        JLabel nomeLabel = new JLabel(nome);
        nomeLabel.setFont(spaceGrotesk.deriveFont(Font.BOLD, 15f));

        JLabel vitsLabel = new JLabel(vitorias);
        vitsLabel.setFont(spaceGrotesk.deriveFont(13f));
        vitsLabel.setForeground(new Color(130, 130, 120));

        row.add(posLabel,  BorderLayout.WEST);
        row.add(nomeLabel, BorderLayout.CENTER);
        row.add(vitsLabel, BorderLayout.EAST);
        return row;
    }
}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new GameClient());
    }
}
