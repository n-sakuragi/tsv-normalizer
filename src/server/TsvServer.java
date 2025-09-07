package server;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.util.*;

public class TsvServer extends NanoHTTPD {
	
    /**
     * ポート8080でサーバーを起動するTsvServerインスタンスを生成するコンストラクタ
     *
     * @throws IOException ソケットの初期化や起動中にI/Oエラーが発生した場合
     */
    public TsvServer() throws IOException {
        super(8080); // ポート8080で待機
        start(SOCKET_READ_TIMEOUT, false);
        System.out.println("サーバー起動中: http://localhost:8080");
    }

    public static void main(String[] args) {
        try {
            new TsvServer();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * HTTP リクエストに応じて以下のレスポンスを返す
     * GET: tsv.html を読み込んで返す
     * POST: TSV データを正規化または逆正規化した結果を返す
     * それ以外: 「POSTでTSVを送ってください」のメッセージを返す
     *
     * @param session リクエスト情報を保持するセッション
     * @return HTTP レスポンス
     */
    @Override
    public Response serve(IHTTPSession session) {
    	
        if (Method.GET.equals(session.getMethod())) {
            // HTML画面を返す（ファイル読み込み）
            try {
                File file = new File("tsv.html");
                if (file.exists()) {
                    FileInputStream fis = new FileInputStream(file);
                    return newChunkedResponse(Response.Status.OK, "text/html", fis);
                } else {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "tsv.html が見つかりません");
                }
            } catch (Exception e) {
                e.printStackTrace();
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "HTML読み込みエラー");
            }
        }

        if (Method.POST.equals(session.getMethod())) {
        	// TSVデータの正規化、または逆正規の処理を行う
            try {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String postData = files.get("postData");

                // postData が null または空文字の場合
                if (postData == null || postData.isEmpty()) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "TSVデータを入力してください");
                }

               // 行ごとに最低限「key\tvalue」が存在するかのチェック
                boolean invalid = Arrays.stream(postData.split("\\R"))
                    .anyMatch(line -> !line.contains("\t"));
                if (invalid) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "TSV形式が不正です。タブ区切りでkeyとvalueを入力してください。");
                }

                // modeがnormalizeの場合は正規化処理、denormalizeの場合は非正規化処理
                List<String> modes = session.getParameters().get("mode");
                String mode = (modes != null && !modes.isEmpty()) ? modes.get(0) : "normalize";
                String result;
                if ("normalize".equals(mode)) {
                    result = normalizeTSV(postData);
                } else if ("denormalize".equals(mode)) {
                    result = denormalizeTSV(postData);
                } else {
                    result = "無効なモードです。";
                }

                // 結果を返す
                return newFixedLengthResponse(Response.Status.OK, "text/plain", result);

                // 例外処理
            } catch (Exception e) {
                e.printStackTrace();
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "エラーが発生しました。");
            }
        }
        return newFixedLengthResponse("POSTでTSVを送ってください");
    }


    /**
     * 入力文字列のTSVデータを分解し、各セルに含まれる値を展開して全組み合わせを生成
     *
     * @param input TSV文字列
     * @return 全組み合わせを結合した文字列
     */
    private String normalizeTSV(String input) {
        StringBuilder output = new StringBuilder();
        String[] lines = input.split("\\R"); // 行ごとに分割

        for (String line : lines) {
        	 // セルに含まれる複数の値をリストのリストに変換
            String[] columns = line.split("\t", -1);
            List<String[]> cells = new ArrayList<>();
            for (String cell : columns) {
                cells.add(cell.split(":"));
            }

            // 全セルの値の組み合わせを改行付きで追加
            generateCombinations(cells, 0, new ArrayList<>(), output);
        }
        return output.toString();
    }

    /**
     * 各セルの候補値から成るリストに基づき、すべての組み合わせを生成して出力に書き込む再帰処理
     *
     * @param row     セルごとに分解された候補値の配列リスト
     * @param index   現在処理中のセルのインデックス
     * @param current 現在ビルド中の組み合わせ（値のリスト）
     * @param output  結果を出力する StringBuilder（完成した組み合わせをタブ区切り＋改行で追加）
     */
    private void generateCombinations(List<String[]> row, int index, List<String> current, StringBuilder output) {
    	// 全てのセルを処理し、現在の組み合わせを出力
        if (index == row.size()) {
            output.append(String.join("\t", current)).append("\n");
            return;
        }

        // 現在のセルの各候補値について処理
        for (String val : row.get(index)) {
            current.add(val); // 候補を追加
            generateCombinations(row, index + 1, current, output); // 次セルへ再帰
            current.remove(current.size() - 1); // 最後に追加した値を削除
        }
    }

    /**
     * TSV形式の入力を「キー毎に値をまとめる」形式に変換する処理
     *
     * @param input 行ごとに「キー\t値」が改行で区切られた文字列
     * @return キー毎にまとめた文字列（各キー行は「キー\t値の結合」形式）
     */
    private String denormalizeTSV(String input) {
        Map<String, List<String>> map = new LinkedHashMap<>(); // 順序を保持するマップ
        String[] lines = input.split("\\R"); // 改行で行に分割

        for (String line : lines) {
            String[] parts = line.split("\t", -1); // タブでキーと値を抽出
            if (parts.length < 2) continue; // 値がない行はスキップ

            String key = parts[0];
            String value = parts[1];

            // キーが未登録なら新規リストを作成
            map.putIfAbsent(key, new ArrayList<>());
            map.get(key).add(value); // キーごとに値を追加
        }

        StringBuilder output = new StringBuilder();
        // キー順に値を「:」で結合して出力
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            output.append(entry.getKey())
                  .append("\t")
                  .append(String.join(":", entry.getValue()))
                  .append("\n");
        }
        return output.toString();
    }
}
